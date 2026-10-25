package cz.teply.sheetset.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cz.teply.sheetset.R
import cz.teply.sheetset.settings.AppLanguages
import cz.teply.sheetset.settings.AppSettings
import cz.teply.sheetset.settings.PageFit
import cz.teply.sheetset.settings.ReaderLayout
import cz.teply.sheetset.settings.ThemeMode
import kotlinx.coroutines.launch

private enum class DrawerScreen {
    MENU,
    LANGUAGE,
    READER,
    GESTURES,
    ANNOTATIONS,
    BACKUP,
    APPEARANCE,
    APP_DETAILS,
}

private enum class ReaderChoice { LAYOUT, PAGE_FIT }

@Composable
fun SettingsDrawer(
    drawerState: DrawerState,
    destination: AppDestination,
    settings: AppSettings,
    onDestination: (AppDestination) -> Unit,
    onSettings: (AppSettings) -> Unit,
    onLanguage: (String?) -> Unit,
    onBackup: () -> Unit,
    onShareBackup: () -> Unit,
    onRestore: () -> Unit,
    content: @Composable () -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(DrawerScreen.MENU) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Closed) screen = DrawerScreen.MENU
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxHeight().widthIn(max = 380.dp).then(
                    if (drawerState.currentValue == DrawerValue.Closed) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                    },
                ),
            ) {
                when (screen) {
                    DrawerScreen.MENU -> DrawerMenu(
                        destination = destination,
                        onDestination = {
                            onDestination(it)
                            scope.launch { drawerState.close() }
                        },
                        onScreen = { screen = it },
                    )
                    DrawerScreen.LANGUAGE -> LanguageSettings(
                        onBack = { screen = DrawerScreen.MENU },
                        onLanguage = onLanguage,
                    )
                    DrawerScreen.READER -> ReaderSettings(
                        settings = settings,
                        onBack = { screen = DrawerScreen.MENU },
                        onGestures = { screen = DrawerScreen.GESTURES },
                        onSettings = onSettings,
                    )
                    DrawerScreen.GESTURES -> GestureSettings(
                        settings = settings,
                        onBack = { screen = DrawerScreen.MENU },
                        onSettings = onSettings,
                    )
                    DrawerScreen.ANNOTATIONS -> AnnotationSettings(
                        settings = settings,
                        onBack = { screen = DrawerScreen.MENU },
                        onSettings = onSettings,
                    )
                    DrawerScreen.BACKUP -> BackupSettings(
                        onBack = { screen = DrawerScreen.MENU },
                        onBackup = onBackup,
                        onShareBackup = onShareBackup,
                        onRestore = onRestore,
                    )
                    DrawerScreen.APPEARANCE -> AppearanceSettings(
                        settings = settings,
                        onBack = { screen = DrawerScreen.MENU },
                        onSettings = onSettings,
                    )
                    DrawerScreen.APP_DETAILS -> AppDetailsSettings(
                        onBack = { screen = DrawerScreen.MENU },
                    )
                }
            }
        },
        content = content,
    )
    BackHandler(
        enabled = drawerState.currentValue == DrawerValue.Open && screen != DrawerScreen.MENU,
    ) {
        screen = DrawerScreen.MENU
    }
}

@Composable
private fun DrawerMenu(
    destination: AppDestination,
    onDestination: (AppDestination) -> Unit,
    onScreen: (DrawerScreen) -> Unit,
) {
    Column(
        Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.menu),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            style = MaterialTheme.typography.headlineSmall,
        )
        SettingsSectionTitle(R.string.settings_library)
        SettingsNavigationRow(
            label = R.string.tab_pdf,
            summary = R.string.pdf_library_summary,
            icon = R.drawable.ic_pdf_24,
            selected = destination == AppDestination.PDF,
        ) { onDestination(AppDestination.PDF) }
        SettingsNavigationRow(
            label = R.string.tab_setlists,
            summary = R.string.setlists_summary,
            icon = R.drawable.ic_setlist_24,
            selected = destination == AppDestination.SETLISTS,
        ) { onDestination(AppDestination.SETLISTS) }

        SettingsSectionTitle(R.string.settings_reading)
        SettingsNavigationRow(
            R.string.reader_page,
            R.string.reader_page_summary,
            R.drawable.ic_pdf_24,
        ) { onScreen(DrawerScreen.READER) }
        SettingsNavigationRow(
            R.string.gestures,
            R.string.gestures_summary,
            R.drawable.ic_straighten_24,
        ) { onScreen(DrawerScreen.GESTURES) }
        SettingsNavigationRow(
            R.string.annotation_tools,
            R.string.annotation_tools_summary,
            R.drawable.ic_edit_24,
        ) { onScreen(DrawerScreen.ANNOTATIONS) }

        SettingsSectionTitle(R.string.settings_data)
        SettingsNavigationRow(
            R.string.backup_restore,
            R.string.backup_restore_summary,
            R.drawable.ic_download_24,
        ) { onScreen(DrawerScreen.BACKUP) }

        SettingsSectionTitle(R.string.settings_app)
        SettingsNavigationRow(
            R.string.language,
            R.string.language_summary,
            R.drawable.ic_language_24,
        ) { onScreen(DrawerScreen.LANGUAGE) }
        SettingsNavigationRow(
            R.string.appearance,
            R.string.appearance_summary,
            R.drawable.ic_dark_mode_24,
        ) { onScreen(DrawerScreen.APPEARANCE) }
        SettingsNavigationRow(
            R.string.about,
            R.string.app_details_summary,
            R.drawable.ic_info_24,
        ) { onScreen(DrawerScreen.APP_DETAILS) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AppearanceSettings(
    settings: AppSettings,
    onBack: () -> Unit,
    onSettings: (AppSettings) -> Unit,
) {
    SettingsPage(R.string.appearance, onBack) {
        item { SettingsSectionTitle(R.string.appearance) }
        item {
            SettingsRadioRow(R.string.theme_light, settings.themeMode == ThemeMode.LIGHT) {
                onSettings(settings.copy(themeMode = ThemeMode.LIGHT))
            }
        }
        item {
            SettingsRadioRow(R.string.theme_dark, settings.themeMode == ThemeMode.DARK) {
                onSettings(settings.copy(themeMode = ThemeMode.DARK))
            }
        }
    }
}

@Composable
private fun LanguageSettings(onBack: () -> Unit, onLanguage: (String?) -> Unit) {
    val selected = AppLanguages.currentTag(LocalContext.current)
    val options = listOf(
        null to R.string.device_language,
        "en" to R.string.language_english,
        "cs" to R.string.language_czech,
        "sk" to R.string.language_slovak,
        "de" to R.string.language_german,
        "pl" to R.string.language_polish,
    )
    SettingsPage(R.string.language, onBack) {
        item { SettingsSectionTitle(R.string.language) }
        items(options, key = { it.first ?: "device" }) { (tag, label) ->
            SettingsRadioRow(label, selected == tag) { onLanguage(tag) }
        }
    }
}

@Composable
private fun ReaderSettings(
    settings: AppSettings,
    onBack: () -> Unit,
    onGestures: () -> Unit,
    onSettings: (AppSettings) -> Unit,
) {
    var choice by rememberSaveable { mutableStateOf<ReaderChoice?>(null) }
    SettingsPage(R.string.reader_page, onBack) {
        item { SettingsSectionTitle(R.string.settings_layout) }
        item {
            SettingsChoiceRow(
                R.string.page_layout,
                readerLayoutLabel(settings.readerLayout),
                R.drawable.ic_view_module_24,
            ) {
                choice = ReaderChoice.LAYOUT
            }
        }
        item {
            SettingsChoiceRow(
                R.string.page_fit,
                pageFitLabel(settings.pageFit),
                R.drawable.ic_straighten_24,
            ) {
                choice = ReaderChoice.PAGE_FIT
            }
        }
        item { SettingsSectionTitle(R.string.settings_page_turning) }
        item {
            SettingsNavigationRow(
                R.string.gestures,
                R.string.page_turning_open_gestures,
                R.drawable.ic_gesture_24,
            ) {
                onGestures()
            }
        }
        item { SettingsSectionTitle(R.string.settings_display) }
        item {
            SettingsSwitchRow(
                R.string.keep_screen_awake,
                R.string.keep_screen_awake_summary,
                settings.keepScreenAwake,
            ) { onSettings(settings.copy(keepScreenAwake = it)) }
        }
        item {
            SettingsSwitchRow(
                R.string.auto_hide_controls,
                R.string.auto_hide_controls_summary,
                settings.autoHideControls,
            ) { onSettings(settings.copy(autoHideControls = it)) }
        }
    }
    when (choice) {
        ReaderChoice.LAYOUT -> SettingsChoiceDialog(
            title = R.string.page_layout,
            selected = settings.readerLayout,
            options = listOf(
                ReaderLayout.SINGLE to R.string.single_page,
                ReaderLayout.HALF to R.string.half_page,
                ReaderLayout.TWO_PAGE to R.string.two_pages,
            ),
            onDismiss = { choice = null },
        ) {
            onSettings(settings.copy(readerLayout = it))
            choice = null
        }
        ReaderChoice.PAGE_FIT -> SettingsChoiceDialog(
            title = R.string.page_fit,
            selected = settings.pageFit,
            options = listOf(
                PageFit.PAGE to R.string.fit_page,
                PageFit.WIDTH to R.string.fit_width,
            ),
            onDismiss = { choice = null },
        ) {
            onSettings(settings.copy(pageFit = it))
            choice = null
        }
        null -> Unit
    }
}

@Composable
private fun GestureSettings(
    settings: AppSettings,
    onBack: () -> Unit,
    onSettings: (AppSettings) -> Unit,
) {
    SettingsPage(R.string.gestures, onBack) {
        item { SettingsSectionTitle(R.string.settings_page_turning) }
        item {
            SettingsSwitchRow(
                R.string.page_turn_taps,
                R.string.page_turn_taps_summary,
                settings.pageTurnTaps,
            ) { onSettings(settings.copy(pageTurnTaps = it)) }
        }
        item {
            SettingsSwitchRow(
                R.string.page_turn_swipes,
                R.string.page_turn_swipes_summary,
                settings.pageTurnSwipes,
            ) { onSettings(settings.copy(pageTurnSwipes = it)) }
        }
        item { SettingsSectionTitle(R.string.settings_zoom) }
        item {
            SettingsInfoRow(
                R.string.pinch_zoom,
                R.string.pinch_zoom_summary,
                R.drawable.ic_search_24,
            )
        }
        item { SettingsSectionTitle(R.string.settings_input) }
        item {
            SettingsSwitchRow(
                R.string.palm_rejection,
                R.string.palm_rejection_summary,
                settings.editor.palmRejection,
            ) {
                onSettings(settings.copy(editor = settings.editor.copy(palmRejection = it)))
            }
        }
    }
}

@Composable
private fun BackupSettings(
    onBack: () -> Unit,
    onBackup: () -> Unit,
    onShareBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    SettingsPage(R.string.backup_restore, onBack) {
        item { SettingsSectionTitle(R.string.settings_backup_actions) }
        item {
            SettingsActionRow(
                R.string.create_backup,
                R.string.create_backup_summary,
                R.drawable.ic_download_24,
                onBackup,
            )
        }
        item {
            SettingsActionRow(
                R.string.share_backup,
                R.string.share_backup_summary,
                R.drawable.ic_share_24,
                onShareBackup,
            )
        }
        item {
            SettingsActionRow(
                R.string.restore_backup,
                R.string.restore_backup_summary,
                R.drawable.ic_upload_file_24,
                onRestore,
            )
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun AppDetailsSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val supportNotice = stringResource(R.string.support_app_notice)
    val version = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    SettingsPage(R.string.about, onBack) {
        item { SettingsSectionTitle(R.string.about) }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_version)) },
                supportingContent = { Text(version) },
                leadingContent = {
                    SettingsLeadingIcon(R.string.app_version, R.drawable.ic_info_24)
                },
            )
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.android_requirement)) },
                leadingContent = {
                    SettingsLeadingIcon(R.string.android_requirement, R.drawable.ic_done_24)
                },
            )
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.privacy_offline)) },
                leadingContent = {
                    SettingsLeadingIcon(R.string.privacy_offline, R.drawable.ic_done_24)
                },
            )
        }
        item { LinkRow(R.string.privacy_policy, R.drawable.ic_info_24, PRIVACY_URL, context) }
        item {
            LinkRow(
                R.string.license,
                R.drawable.ic_pdf_24,
                "$REPOSITORY_URL/blob/main/LICENSE",
                context,
            )
        }
        item { LinkRow(R.string.github_repository, R.drawable.ic_share_24, REPOSITORY_URL, context) }
        item {
            LinkRow(
                R.string.release_page,
                R.drawable.ic_download_24,
                "$REPOSITORY_URL/releases",
                context,
            )
        }
        item {
            Button(
                onClick = {
                    Toast.makeText(context, supportNotice, Toast.LENGTH_SHORT).show()
                    runCatching { uriHandler.openUri(SUPPORT_URL) }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth().heightIn(min = 56.dp),
                border = BorderStroke(1.dp, Color(0xFF111111)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFDD00),
                    contentColor = Color(0xFF111111),
                ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_coffee),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.support_app))
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
internal fun SettingsPage(
    @StringRes title: Int,
    onBack: () -> Unit,
    listState: LazyListState? = null,
    content: LazyListScope.() -> Unit,
) {
    val back = stringResource(R.string.back)
    val fallbackListState = rememberLazyListState()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                modifier = Modifier.semantics { contentDescription = back },
                onClick = onBack,
            ) {
                Icon(painterResource(R.drawable.ic_chevron_left_24), contentDescription = null)
            }
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.weight(1f).testTag("settings-list"),
            state = listState ?: fallbackListState,
            content = content,
        )
    }
}

@Composable
internal fun SettingsSectionTitle(@StringRes label: Int) {
    Text(
        text = stringResource(label),
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsNavigationRow(
    @StringRes label: Int,
    @StringRes summary: Int,
    @DrawableRes icon: Int? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .semantics { this.selected = selected }
            .clickable(onClick = onClick),
        headlineContent = { Text(stringResource(label)) },
        supportingContent = {
            Text(stringResource(summary), maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = icon?.let { drawable ->
            { SettingsLeadingIcon(label, drawable) }
        },
        trailingContent = {
            Icon(painterResource(R.drawable.ic_chevron_right_24), contentDescription = null)
        },
    )
}

@Composable
internal fun SettingsSwitchRow(
    @StringRes label: Int,
    @StringRes summary: Int,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked),
        headlineContent = { Text(stringResource(label)) },
        supportingContent = { Text(stringResource(summary)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

@Composable
internal fun SettingsChoiceRow(
    @StringRes label: Int,
    @StringRes currentValue: Int,
    @DrawableRes icon: Int? = null,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick),
        headlineContent = { Text(stringResource(label)) },
        supportingContent = { Text(stringResource(currentValue)) },
        leadingContent = icon?.let { drawable ->
            { SettingsLeadingIcon(label, drawable) }
        },
        trailingContent = {
            Icon(painterResource(R.drawable.ic_chevron_right_24), contentDescription = null)
        },
    )
}

@Composable
private fun SettingsInfoRow(
    @StringRes label: Int,
    @StringRes summary: Int,
    @DrawableRes icon: Int? = null,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        headlineContent = { Text(stringResource(label)) },
        supportingContent = { Text(stringResource(summary)) },
        leadingContent = icon?.let { drawable ->
            { SettingsLeadingIcon(label, drawable) }
        },
    )
}

@Composable
private fun SettingsActionRow(
    @StringRes label: Int,
    @StringRes summary: Int,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick),
        headlineContent = { Text(stringResource(label)) },
        supportingContent = { Text(stringResource(summary)) },
        leadingContent = { SettingsLeadingIcon(label, icon) },
        trailingContent = {
            Icon(painterResource(R.drawable.ic_chevron_right_24), contentDescription = null)
        },
    )
}

@Composable
private fun SettingsRadioRow(@StringRes label: Int, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        headlineContent = { Text(stringResource(label)) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
    )
}

@Composable
internal fun <T> SettingsChoiceDialog(
    @StringRes title: Int,
    selected: T,
    options: List<Pair<T, Int>>,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    SettingsRadioRow(label, value == selected) { onSelect(value) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun LinkRow(
    @StringRes label: Int,
    @DrawableRes icon: Int,
    url: String,
    context: Context,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { openUrl(context, url) },
        headlineContent = { Text(stringResource(label)) },
        leadingContent = { SettingsLeadingIcon(label, icon) },
        trailingContent = {
            Icon(painterResource(R.drawable.ic_chevron_right_24), contentDescription = null)
        },
    )
}

@Composable
private fun SettingsLeadingIcon(@StringRes label: Int, @DrawableRes icon: Int) {
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        modifier = Modifier.size(24.dp).testTag("settings-icon:$label:$icon"),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@StringRes
private fun readerLayoutLabel(value: ReaderLayout): Int = when (value) {
    ReaderLayout.SINGLE -> R.string.single_page
    ReaderLayout.HALF -> R.string.half_page
    ReaderLayout.TWO_PAGE -> R.string.two_pages
}

@StringRes
private fun pageFitLabel(value: PageFit): Int = when (value) {
    PageFit.PAGE -> R.string.fit_page
    PageFit.WIDTH -> R.string.fit_width
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
}

private const val REPOSITORY_URL = "https://github.com/Majkey25/selia-lists"
private const val PRIVACY_URL = "https://majkey25.github.io/selia-lists/privacy.html"
private const val SUPPORT_URL = "https://www.buymeacoffee.com/majkey"
