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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val REPOSITORY_URL = "https://github.com/Cedar17/zju-connect-android"
private const val REPORT_ISSUES_URL = "https://github.com/Cedar17/zju-connect-android/issues"
private const val CORE_REPOSITORY_NAME = "zju-connect"
private const val CORE_REPOSITORY_URL = "https://github.com/Mythologyli/zju-connect"

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
                    onOpenCoreRepository = {
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CORE_REPOSITORY_URL)))
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
    onOpenCoreRepository: () -> Unit,
    onOpenReportProblem: () -> Unit,
) {
    val iconDescription = stringResource(R.string.about_app_icon_description)
    val description = stringResource(R.string.about_description, CORE_REPOSITORY_NAME)
    val descriptionText = buildAnnotatedString {
        val linkStart = description.indexOf(CORE_REPOSITORY_NAME)
        if (linkStart < 0) {
            append(description)
        } else {
            append(description.substring(0, linkStart))
            withLink(
                LinkAnnotation.Url(
                    url = CORE_REPOSITORY_URL,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                    linkInteractionListener = LinkInteractionListener { onOpenCoreRepository() },
                ),
            ) {
                append(CORE_REPOSITORY_NAME)
            }
            append(description.substring(linkStart + CORE_REPOSITORY_NAME.length))
        }
    }

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
            horizontalAlignment = Alignment.Start,
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.about_version, version),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = descriptionText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = onOpenRepository,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        text = stringResource(R.string.about_repository),
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start,
                        textDecoration = TextDecoration.Underline,
                    )
                }
                TextButton(
                    onClick = onOpenReportProblem,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        text = stringResource(R.string.about_report_problem),
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start,
                        textDecoration = TextDecoration.Underline,
                    )
                }
            }
        }
    }
}
