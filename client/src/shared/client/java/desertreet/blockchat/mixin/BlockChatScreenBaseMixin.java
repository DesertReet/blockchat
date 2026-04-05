package desertreet.blockchat.mixin;

import desertreet.blockchat.ui.DraftComposerWidget;
import desertreet.blockchat.ui.InboxSnapViewerWidget;
import desertreet.blockchat.ui.SendRecipientsWidget;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "desertreet.blockchat.ui.BlockChatScreenBase")
abstract class BlockChatScreenBaseMixin {

	@Shadow
	private DraftComposerWidget composer;

	@Shadow
	private boolean showingComposer;

	@Shadow
	private SendRecipientsWidget recipientPicker;

	@Shadow
	private boolean showingRecipientPicker;

	@Shadow
	private InboxSnapViewerWidget inboxViewer;

	@Shadow
	private boolean loadingInboxViewer;

	@Shadow
	private boolean showingDeleteAccountModal;

	@Inject(method = "renderTabs", at = @At("HEAD"), cancellable = true)
	private void blockchat$hideTabsWhileOverlayOpen(GuiGraphics graphics, boolean selectedOnly, CallbackInfo ci) {
		if (showingDeleteAccountModal
			|| (showingRecipientPicker && recipientPicker != null)
			|| (showingComposer && composer != null)
			|| loadingInboxViewer
			|| inboxViewer != null) {
			ci.cancel();
		}
	}
}
