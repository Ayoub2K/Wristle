package dev.ayoub.wristle.tile

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonColors
import androidx.wear.protolayout.material.ButtonDefaults
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dev.ayoub.wristle.MainActivity
import dev.ayoub.wristle.R

/** Tile with one large mic button; tapping it opens the app, which starts listening for a new question. */
class AskTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(tile(requestParams.deviceConfiguration))
            "ask-tile"
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(
                ResourceBuilders.Resources
                    .Builder()
                    .setVersion(RESOURCES_VERSION)
                    .addIdToImageMapping(
                        ID_MIC,
                        ResourceBuilders.ImageResource
                            .Builder()
                            .setAndroidResourceByResId(
                                ResourceBuilders.AndroidImageResourceByResId
                                    .Builder()
                                    .setResourceId(R.drawable.ic_mic)
                                    .build(),
                            ).build(),
                    ).build(),
            )
            "ask-tile-resources"
        }

    private fun tile(device: DeviceParameters): TileBuilders.Tile {
        val openApp =
            ModifiersBuilders.Clickable
                .Builder()
                .setId("ask")
                .setOnClick(
                    ActionBuilders.LaunchAction
                        .Builder()
                        .setAndroidActivity(
                            ActionBuilders.AndroidActivity
                                .Builder()
                                .setPackageName(packageName)
                                .setClassName(MainActivity::class.java.name)
                                .addKeyToExtraMapping(MainActivity.EXTRA_LISTEN, ActionBuilders.booleanExtra(true))
                                .build(),
                        ).build(),
                ).build()
        val mic =
            Button
                .Builder(this, openApp)
                .setIconContent(ID_MIC)
                .setSize(ButtonDefaults.LARGE_SIZE)
                .setButtonColors(ButtonColors(ACCENT, WHITE))
                .setContentDescription(getString(R.string.chat_ask_by_voice))
                .build()
        val layout =
            PrimaryLayout
                .Builder(device)
                .setResponsiveContentInsetEnabled(true)
                .setPrimaryLabelTextContent(
                    Text
                        .Builder(this, getString(R.string.app_name))
                        .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                        .setColor(argb(ACCENT))
                        .build(),
                ).setContent(mic)
                .setSecondaryLabelTextContent(
                    Text
                        .Builder(this, getString(R.string.chat_hint))
                        .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                        .setColor(argb(WHITE))
                        .build(),
                ).build()
        return TileBuilders.Tile
            .Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
    }

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val ID_MIC = "mic"

        // Same terracotta as ui/Colors.kt; tiles take plain ARGB ints.
        const val ACCENT = 0xFFD97757.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
