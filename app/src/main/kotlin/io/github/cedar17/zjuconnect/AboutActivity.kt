package io.github.cedar17.zjuconnect

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val REPOSITORY_URL = "https://github.com/Cedar17/zju-connect-android"
private const val REPORT_ISSUES_URL = "https://github.com/Cedar17/zju-connect-android/issues"

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZjuConnectTheme {
                AboutScreen(
                    version = packageVersionName(),
                    onBack = ::finish,
                    onOpenRepository = {
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL)))
                        }
                    },
                    onOpenReportProblem = {
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPORT_ISSUES_URL)))
                        }
                    },
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun packageVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty().ifBlank { "unknown" }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AboutScreen(
    version: String,
    onBack: () -> Unit,
    onOpenRepository: () -> Unit,
    onOpenReportProblem: () -> Unit,
) {
    val iconDescription = stringResource(R.string.about_app_icon_description)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.back))
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AndroidView(
                factory = { context -> ImageView(context) },
                update = { imageView ->
                    imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                    imageView.setImageResource(R.mipmap.ic_launcher)
                    imageView.contentDescription = iconDescription
                },
                modifier = Modifier
                    .size(112.dp),
            )
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                text = stringResource(R.string.about_version, version),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.about_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpenRepository) {
                    Text(
                        text = stringResource(R.string.about_repository),
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    )
                }
                TextButton(onClick = onOpenReportProblem) {
                    Text(
                        text = stringResource(R.string.about_report_problem),
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    )
                }
            }
        }
    }
}
