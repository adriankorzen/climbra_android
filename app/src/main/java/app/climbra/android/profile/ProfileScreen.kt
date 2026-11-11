package app.climbra.android.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.climbra.android.R
import app.climbra.android.session.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class ProfileViewModel @Inject constructor(private val session: SessionRepository) : ViewModel() {
    fun logout() {
        viewModelScope.launch { session.logout() }
    }
}

@Composable
fun ProfileRoute(email: String, viewModel: ProfileViewModel = hiltViewModel()) {
    ProfileScreen(email = email, onLogout = viewModel::logout)
}

/** Placeholder of the profile until its spec: the account and logout. */
@Composable
fun ProfileScreen(email: String, onLogout: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.nav_profile), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.profile_signed_in_as, email),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onLogout, modifier = Modifier.testTag("profile-logout")) {
            Icon(painterResource(R.drawable.ic_logout), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.logout), modifier = Modifier.padding(start = 8.dp))
        }
    }
}
