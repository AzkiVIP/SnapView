package com.azkivip.snapview.client.gui;

import com.azkivip.snapview.client.screenshot.ScreenshotEntry;
import com.azkivip.snapview.client.screenshot.ScreenshotFileUtil;
import com.azkivip.snapview.client.screenshot.ScreenshotRepository;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.function.Consumer;

public final class RenameScreen extends Screen {

        private final Screen parent;
        private final ScreenshotRepository repository;
        private final ScreenshotEntry entry;
        private final Consumer<ScreenshotEntry> onRenamed;

        private EditBox nameField;
        private Component errorMessage = Component.empty();

        public RenameScreen(Screen parent, ScreenshotRepository repository, ScreenshotEntry entry,
                             Consumer<ScreenshotEntry> onRenamed) {
                super(Component.translatable("snapview.rename.title"));
                this.parent = parent;
                this.repository = repository;
                this.entry = entry;
                this.onRenamed = onRenamed;
        }

        @Override
        protected void init() {
                String currentBaseName = entry.fileName().endsWith(".png")
                                ? entry.fileName().substring(0, entry.fileName().length() - 4)
                                : entry.fileName();

                int fieldWidth = 220;
                int centerX = this.width / 2;
                int fieldY = this.height / 2 - 10;

                nameField = new EditBox(this.font, centerX - fieldWidth / 2, fieldY, fieldWidth, 20, Component.empty());
                nameField.setMaxLength(200);
                nameField.setValue(currentBaseName);
                // EditBox#setHighlighted(boolean) is gone in 26.1. The closest equivalent is
                // selecting the whole field contents so the user can over-type them by default:
                // cursor at 0, highlight anchor at the end of the string.
                nameField.setCursorPosition(0);
                nameField.setHighlightPos(currentBaseName.length());
                addRenderableWidget(nameField);
                setInitialFocus(nameField);

                addRenderableWidget(Button.builder(Component.translatable("snapview.rename.confirm"),
                                                b -> confirm())
                                .bounds(centerX - fieldWidth / 2, fieldY + 28, fieldWidth / 2 - 4, 20)
                                .build());
                addRenderableWidget(Button.builder(Component.translatable("snapview.rename.cancel"),
                                                b -> this.minecraft.setScreen(parent))
                                .bounds(centerX + 4, fieldY + 28, fieldWidth / 2 - 4, 20)
                                .build());
        }

        private void confirm() {
                ScreenshotFileUtil.ValidationResult result = ScreenshotFileUtil.validate(
                                nameField.getValue(), entry.path().getParent(), entry.fileName());

                switch (result) {
                        case ScreenshotFileUtil.ValidationResult.Empty ignored ->
                                        errorMessage = Component.translatable("snapview.rename.error.empty");
                        case ScreenshotFileUtil.ValidationResult.Invalid ignored ->
                                        errorMessage = Component.translatable("snapview.rename.error.invalid");
                        case ScreenshotFileUtil.ValidationResult.Exists ignored ->
                                        errorMessage = Component.translatable("snapview.rename.error.exists");
                        case ScreenshotFileUtil.ValidationResult.Valid valid -> {
                                try {
                                        ScreenshotEntry renamed = ScreenshotFileUtil.rename(entry, valid.normalizedName());
                                        repository.replaceEntry(entry, renamed);
                                        onRenamed.accept(renamed);
                                        this.minecraft.setScreen(parent);
                                } catch (IOException e) {
                                        errorMessage = Component.translatable("snapview.rename.error.failed", e.getMessage());
                                }
                        }
                }
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
                super.extractRenderState(graphics, mouseX, mouseY, partialTick);
                // 26.1 split GuiGraphics into GuiGraphicsExtractor; drawCenteredString was
                // renamed to centeredText along the way.
                graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 32, 0xFFFFFF);
                if (!errorMessage.getString().isEmpty()) {
                        graphics.centeredText(this.font, errorMessage, this.width / 2, this.height / 2 + 54, 0xFF5555);
                }
        }

        @Override
        public void onClose() {
                this.minecraft.setScreen(parent);
        }
}
