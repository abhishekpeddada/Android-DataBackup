package com.xayah.feature.main.cloud.add

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.xayah.core.model.database.GoogleDriveExtra
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.confirm
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.component.paddingStart
import com.xayah.core.ui.component.paddingTop
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.theme.withState
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.GsonUtil
import com.xayah.feature.main.cloud.AccountSetupScaffold
import com.xayah.feature.main.cloud.R
import com.xayah.feature.main.cloud.SetupTextField

@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageGoogleDriveSetup() {
    val dialogState = LocalSlotScope.current!!.dialogSlot
    val context = LocalContext.current
    val navController = LocalNavController.current!!
    val viewModel = hiltViewModel<IndexViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    
    var name by rememberSaveable { mutableStateOf(uiState.currentName) }
    var accountEmail by rememberSaveable(uiState.cloudEntity) { 
        mutableStateOf(uiState.cloudEntity?.getExtraEntity<GoogleDriveExtra>()?.accountEmail ?: "") 
    }
    var folderId by rememberSaveable(uiState.cloudEntity) { 
        mutableStateOf(uiState.cloudEntity?.getExtraEntity<GoogleDriveExtra>()?.folderId ?: "root") 
    }
    val allFilled by rememberSaveable(name, accountEmail) { 
        mutableStateOf(name.isNotEmpty() && accountEmail.isNotEmpty()) 
    }

    LaunchedEffect(null) {
        viewModel.emitIntentOnIO(IndexUiIntent.Initialize)
    }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            task.addOnSuccessListener { account ->
                accountEmail = account.email ?: ""
                if (name.isEmpty()) {
                    name = account.displayName ?: account.email ?: "Google Drive"
                }
                if (folderId.isEmpty() || folderId == "root") {
                    folderId = "root"
                }
                viewModel.launchOnIO {
                    val extra = GsonUtil().toJson(GoogleDriveExtra(accountEmail = accountEmail, folderId = folderId))
                    viewModel.emitIntent(
                        IndexUiIntent.UpdateEntity(
                            name = name,
                            remote = folderId,
                            type = com.xayah.core.model.CloudType.GOOGLE_DRIVE,
                            url = "",  
                            username = accountEmail,
                            password = "",  
                            extra = extra
                        )
                    )
                }
            }
            task.addOnFailureListener { exception ->
                viewModel.launchOnIO {
                    viewModel.snackbarHostState.showSnackbar(
                        message = context.getString(R.string.sign_in_failed, exception.localizedMessage ?: "Unknown error")
                    )
                }
            }
        }
    }

    fun startGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(
                Scope(DriveScopes.DRIVE_FILE),
                Scope(DriveScopes.DRIVE_APPDATA)
            )
            .build()

        val googleSignInClient = GoogleSignIn.getClient(context, gso)
        googleSignInClient.signOut().addOnCompleteListener {
            signInLauncher.launch(googleSignInClient.signInIntent)
        }
    }

    AccountSetupScaffold(
        scrollBehavior = scrollBehavior,
        snackbarHostState = viewModel.snackbarHostState,
        title = stringResource(id = R.string.google_drive_setup),
        actions = {
            TextButton(
                enabled = allFilled && uiState.isProcessing.not(),
                onClick = {
                    viewModel.launchOnIO {
                        val extra = GsonUtil().toJson(GoogleDriveExtra(accountEmail = accountEmail, folderId = folderId))
                        viewModel.emitIntent(
                            IndexUiIntent.UpdateEntity(
                                name = name,
                                remote = "",  
                                type = com.xayah.core.model.CloudType.GOOGLE_DRIVE,
                                url = "",
                                username = accountEmail,
                                password = "",
                                extra = extra
                            )
                        )
                        viewModel.emitIntent(IndexUiIntent.TestConnection)
                        
                        viewModel.emitIntent(IndexUiIntent.SetRemotePath(context = context))
                        
                        uiState.cloudEntity?.let { entity ->
                            val updatedExtra = entity.getExtraEntity<GoogleDriveExtra>()
                            updatedExtra?.folderId?.let { actualFolderId ->
                                if (actualFolderId.isNotEmpty()) {
                                    folderId = actualFolderId
                                }
                            }
                        }
                    }
                }
            ) {
                Text(text = stringResource(id = R.string.test_connection))
            }

            Button(
                enabled = allFilled && uiState.isProcessing.not(), 
                onClick = {
                    viewModel.launchOnIO {
                        val extra = GsonUtil().toJson(GoogleDriveExtra(accountEmail = accountEmail, folderId = folderId))
                        viewModel.emitIntent(
                            IndexUiIntent.UpdateEntity(
                                name = name,
                                remote = "",  // Empty for Google Drive - paths are relative to folder ID
                                type = com.xayah.core.model.CloudType.GOOGLE_DRIVE,
                                url = "",
                                username = accountEmail,
                                password = "",
                                extra = extra
                            )
                        )
                        viewModel.emitIntent(IndexUiIntent.CreateAccount(navController = navController))
                    }
                }
            ) {
                Text(text = stringResource(id = R.string._continue))
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)
        ) {
            Title(
                enabled = uiState.isProcessing.not(), 
                title = stringResource(id = R.string.account), 
                verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)
            ) {
                SetupTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.currentName.isEmpty() && uiState.isProcessing.not(),
                    value = name,
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_badge),
                    onValueChange = { name = it },
                    label = stringResource(id = R.string.name)
                )

                Clickable(
                    enabled = uiState.isProcessing.not(),
                    icon = Icons.Rounded.AccountCircle,
                    title = stringResource(id = R.string.sign_in_with_google),
                    value = accountEmail.ifEmpty { stringResource(id = R.string.not_signed_in) },
                    desc = stringResource(id = R.string.sign_in_with_google_desc),
                ) {
                    startGoogleSignIn()
                }
            }

            Title(
                enabled = uiState.isProcessing.not(), 
                title = stringResource(id = R.string.advanced)
            ) {
                Clickable(
                    enabled = false, 
                    icon = Icons.Rounded.CloudDone,
                    title = stringResource(id = R.string.backup_folder),
                    value = "Auto-creates 'DataBackup' folder",
                    desc = "The app will automatically create a 'DataBackup' folder in your Google Drive root",
                ) {
                    // Folder picker will be implemented in future update
                }

                if (uiState.currentName.isNotEmpty())
                    TextButton(
                        modifier = Modifier
                            .paddingStart(SizeTokens.Level12)
                            .paddingTop(SizeTokens.Level12),
                        enabled = uiState.isProcessing.not(),
                        onClick = {
                            viewModel.launchOnIO {
                                if (dialogState.confirm(
                                        title = context.getString(R.string.delete_account), 
                                        text = context.getString(R.string.delete_account_desc)
                                    )) {
                                    viewModel.emitIntent(IndexUiIntent.DeleteAccount(navController = navController))
                                }
                            }
                        }
                    ) {
                        Text(
                            text = stringResource(id = R.string.delete_account),
                            color = ThemedColorSchemeKeyTokens.Error.value.withState(uiState.isProcessing.not())
                        )
                    }
            }
        }
    }
}
