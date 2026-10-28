package com.termux.app.plugins;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.card.MaterialCardView;
import com.termux.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** RecyclerView adapter for installed, bundled, and repository plugin cards. */
public final class PluginCardAdapter extends RecyclerView.Adapter<PluginCardAdapter.CardViewHolder> {

    public interface Listener {
        void onPrimaryAction(PluginCard card);

        void onDetails(PluginCard card);

        void onUninstall(PluginCard card);
    }

    private final Listener mListener;
    private final Map<String, Long> mStableIds = new HashMap<>();
    private long mNextStableId = 1;
    private List<PluginCard> mCards = new ArrayList<>();

    public PluginCardAdapter(Listener listener) {
        mListener = listener;
        setHasStableIds(true);
    }

    public void setCards(List<PluginCard> cards) {
        final List<PluginCard> replacement = cards == null ? new ArrayList<PluginCard>()
            : new ArrayList<>(cards);
        final List<PluginCard> old = mCards;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return old.size();
            }

            @Override
            public int getNewListSize() {
                return replacement.size();
            }

            @Override
            public boolean areItemsTheSame(int oldPosition, int newPosition) {
                PluginCard left = old.get(oldPosition);
                PluginCard right = replacement.get(newPosition);
                return left.kind == right.kind && left.id.equals(right.id);
            }

            @Override
            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                return old.get(oldPosition).equals(replacement.get(newPosition));
            }
        }, true);
        mCards = replacement;
        diff.dispatchUpdatesTo(this);
    }

    public void clearCards() {
        setCards(new ArrayList<PluginCard>());
    }

    @Override
    public long getItemId(int position) {
        PluginCard card = mCards.get(position);
        String key = card.kind.name() + ":" + card.id;
        Long stableId = mStableIds.get(key);
        if (stableId == null) {
            stableId = mNextStableId++;
            mStableIds.put(key, stableId);
        }
        return stableId;
    }

    @NonNull
    @Override
    public CardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.view_plugin_card, parent, false);
        return new CardViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CardViewHolder holder, int position) {
        holder.bind(mCards.get(position), mListener);
    }

    @Override
    public int getItemCount() {
        return mCards.size();
    }

    /** Returns the card shown at the given position, or null when the position is invalid. */
    @Nullable
    public PluginCard getCard(int position) {
        if (position < 0 || position >= mCards.size()) return null;
        return mCards.get(position);
    }

    static final class CardViewHolder extends RecyclerView.ViewHolder {
        private final MaterialCardView mCard;
        private final TextView mName;
        private final TextView mVersion;
        private final Chip mType;
        private final Chip mStatus;
        private final TextView mDescription;
        private final TextView mMetadata;
        private final TextView mPermissions;
        private final TextView mDependencies;
        private final TextView mStatusDetail;
        private final MaterialButton mPrimaryButton;
        private final MaterialButton mDetailsButton;
        private final MaterialButton mUninstallButton;

        CardViewHolder(View itemView) {
            super(itemView);
            mCard = (MaterialCardView) itemView;
            mName = itemView.findViewById(R.id.plugin_name);
            mVersion = itemView.findViewById(R.id.plugin_version);
            mType = itemView.findViewById(R.id.plugin_type);
            mStatus = itemView.findViewById(R.id.plugin_status);
            mDescription = itemView.findViewById(R.id.plugin_description);
            mMetadata = itemView.findViewById(R.id.plugin_metadata);
            mPermissions = itemView.findViewById(R.id.plugin_permissions);
            mDependencies = itemView.findViewById(R.id.plugin_dependencies);
            mStatusDetail = itemView.findViewById(R.id.plugin_status_detail);
            mPrimaryButton = itemView.findViewById(R.id.plugin_primary_action);
            mDetailsButton = itemView.findViewById(R.id.plugin_details_action);
            mUninstallButton = itemView.findViewById(R.id.plugin_uninstall_action);
        }

        void bind(final PluginCard card, final Listener listener) {
            mName.setText(card.name);
            mVersion.setText(card.version);
            mVersion.setVisibility(card.version.isEmpty() ? View.GONE : View.VISIBLE);
            mType.setText(card.type);
            mType.setVisibility(card.type.isEmpty() ? View.GONE : View.VISIBLE);
            mStatus.setText(statusText(card));
            styleStatusChip(card.status);
            mDescription.setText(card.description);
            mDescription.setVisibility(card.description.isEmpty() ? View.GONE : View.VISIBLE);
            mMetadata.setText(card.metadata);
            mMetadata.setVisibility(card.metadata.isEmpty() ? View.GONE : View.VISIBLE);
            mPermissions.setText(itemView.getContext().getString(R.string.plugins_permissions,
                join(card.permissions)));
            mPermissions.setVisibility(card.permissions.isEmpty() ? View.GONE : View.VISIBLE);
            mDependencies.setText(itemView.getContext().getString(R.string.plugins_dependencies,
                join(card.dependencies)));
            mDependencies.setVisibility(card.dependencies.isEmpty() ? View.GONE : View.VISIBLE);
            mStatusDetail.setText(card.statusDetail);
            mStatusDetail.setVisibility(card.statusDetail.isEmpty() ? View.GONE : View.VISIBLE);
            mStatusDetail.setTextColor(ContextCompat.getColor(itemView.getContext(),
                card.status == PluginCard.Status.NEEDS_ATTENTION
                    ? R.color.plugin_state_error : R.color.plugin_state_notice));

            switch (card.primaryAction) {
                case ENABLE:
                    mPrimaryButton.setText(R.string.plugins_enable);
                    break;
                case DISABLE:
                    mPrimaryButton.setText(R.string.plugins_disable);
                    break;
                case INSTALL:
                    mPrimaryButton.setText(R.string.plugins_install);
                    break;
                case UPDATE:
                    mPrimaryButton.setText(R.string.plugins_update);
                    break;
                default:
                    mPrimaryButton.setText(R.string.plugins_details);
                    break;
            }
            if (card.primaryAction != PluginCard.PrimaryAction.NONE) {
                mPrimaryButton.setContentDescription(itemView.getContext().getString(
                    R.string.plugins_action_accessibility, card.name,
                    mPrimaryButton.getText()));
            } else {
                mPrimaryButton.setContentDescription(null);
            }
            mPrimaryButton.setVisibility(card.primaryAction == PluginCard.PrimaryAction.NONE
                ? View.GONE : View.VISIBLE);
            mPrimaryButton.setOnClickListener(card.primaryAction == PluginCard.PrimaryAction.NONE
                ? null : new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        listener.onPrimaryAction(card);
                    }
                });

            mDetailsButton.setVisibility(card.detailsAvailable ? View.VISIBLE : View.GONE);
            mDetailsButton.setContentDescription(itemView.getContext().getString(
                R.string.plugins_action_accessibility, card.name,
                itemView.getContext().getString(R.string.plugins_details)));
            mDetailsButton.setOnClickListener(card.detailsAvailable ? new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    listener.onDetails(card);
                }
            } : null);

            mUninstallButton.setVisibility(card.kind == PluginCard.Kind.INSTALLED
                ? View.VISIBLE : View.GONE);
            mUninstallButton.setContentDescription(itemView.getContext().getString(
                R.string.plugins_action_accessibility, card.name,
                itemView.getContext().getString(R.string.plugins_uninstall)));
            mUninstallButton.setOnClickListener(card.kind == PluginCard.Kind.INSTALLED
                ? new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        listener.onUninstall(card);
                    }
                } : null);
            mCard.setContentDescription(itemView.getContext().getString(
                R.string.plugins_card_content_description, card.name, card.version, card.type));
        }

        private void styleStatusChip(PluginCard.Status status) {
            int background;
            int foreground;
            switch (status) {
                case ENABLED:
                    background = R.color.plugin_status_enabled_background;
                    foreground = R.color.plugin_status_enabled_text;
                    break;
                case OVERRIDDEN:
                    background = R.color.plugin_status_overridden_background;
                    foreground = R.color.plugin_status_overridden_text;
                    break;
                case NEEDS_ATTENTION:
                case INERT:
                    background = R.color.plugin_status_attention_background;
                    foreground = R.color.plugin_status_attention_text;
                    break;
                default:
                    background = R.color.plugin_status_disabled_background;
                    foreground = R.color.plugin_status_disabled_text;
                    break;
            }
            mStatus.setChipBackgroundColorResource(background);
            mStatus.setTextColor(ContextCompat.getColor(itemView.getContext(), foreground));
        }

        private String statusText(PluginCard card) {
            switch (card.status) {
                case ENABLED:
                    return itemView.getContext().getString(R.string.plugins_status_enabled);
                case DISABLED:
                    return itemView.getContext().getString(R.string.plugins_status_disabled);
                case NEEDS_ATTENTION:
                    return itemView.getContext().getString(R.string.plugins_status_needs_attention);
                case OVERRIDDEN:
                    return itemView.getContext().getString(R.string.plugins_status_overridden);
                case INERT:
                    return itemView.getContext().getString(R.string.plugins_status_inert);
                case INSTALLED:
                    return itemView.getContext().getString(R.string.plugins_status_installed);
                case AVAILABLE:
                    return itemView.getContext().getString(R.string.plugins_status_available);
                case UPDATE_AVAILABLE:
                    return itemView.getContext().getString(R.string.plugins_status_update_available);
                case UP_TO_DATE:
                    return itemView.getContext().getString(R.string.plugins_status_up_to_date);
                case INCOMPATIBLE:
                    return itemView.getContext().getString(R.string.plugins_status_incompatible);
                default:
                    return "";
            }
        }

        private String join(List<String> values) {
            if (values.isEmpty()) return itemView.getContext().getString(R.string.plugins_none);
            StringBuilder builder = new StringBuilder();
            for (String value : values) {
                if (builder.length() > 0) builder.append('\n');
                builder.append(value);
            }
            return builder.toString();
        }
    }
}
