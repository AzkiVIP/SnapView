package com.azkivip.snapview.client.mixin;

import com.azkivip.snapview.client.SnapViewClient;
import com.azkivip.snapview.client.gui.GalleryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into {@link TitleScreen} to add a compact icon button for opening
 * the SnapView screenshot gallery.
 * <p>
 * The button is a small 20×20 square placed on the LEFT side of the bottom
 * button row (same row as Accessibility and Options buttons). It uses a
 * camera emoji "📷" as the icon and shows a tooltip "Screenshot Gallery"
 * on hover.
 * <p>
 * Vanilla places the Accessibility button at {@code width/2 - 124}. We place
 * our button to the LEFT of it, at {@code width/2 - 148}, with a small gap.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    private TitleScreenMixin() {
        super(null);
    }

    @Inject(method = "createNormalMenuOptions", at = @At("RETURN"))
    private void snapview$addGalleryButton(int y, int ySpacing,
                                           CallbackInfoReturnable<Integer> cir) {
        // Place a small 20x20 icon button on the LEFT side of the bottom row.
        // Vanilla places:
        //   Accessibility button at width/2 - 124 (20x20)
        //   Options button at width/2 + 104 (20x20)
        // We place our button to the LEFT of Accessibility, at width/2 - 148,
        // with a 4px gap between our button and the Accessibility button.
        int buttonSize = 20;
        int buttonX = this.width / 2 - 148;
        int buttonY = y - buttonSize - 4; // align with the bottom button row

        Button galleryButton = Button.builder(
                        Component.literal("\uD83D\uDCF7"), // camera emoji 📷 (UTF-16 surrogate pair)
                        button -> {
                            Minecraft client = Minecraft.getInstance();
                            SnapViewClient snapView = SnapViewClient.getInstance();
                            if (snapView != null) {
                                client.setScreen(new GalleryScreen(
                                        this,
                                        snapView.getRepository(),
                                        snapView.getThumbnailLoader()));
                            }
                        })
                .bounds(buttonX, buttonY, buttonSize, buttonSize)
                .tooltip(Tooltip.create(Component.translatable("snapview.title.gallery")))
                .build();

        this.addRenderableWidget(galleryButton);
    }
}
