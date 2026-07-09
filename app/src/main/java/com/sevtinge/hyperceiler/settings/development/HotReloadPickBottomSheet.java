/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.settings.development;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.sevtinge.hyperceiler.R;
import com.sevtinge.hyperceiler.core.R.string;

import java.util.List;

import fan.bottomsheet.BottomSheetBehavior;
import fan.bottomsheet.BottomSheetModal;
import fan.core.utils.HyperMaterialUtils;
import fan.core.utils.RomUtils;
import io.github.libxposed.service.HookedTarget;

/**
 * 热重载 - 进程选择 Bottom Sheet。
 *
 * <p>列出当前可热重载的目标进程；点击某项后通过 {@link OnTargetPickedListener} 回调。</p>
 */
public class HotReloadPickBottomSheet extends BottomSheetModal {

    private final Activity mActivity;
    private OnTargetPickedListener mListener;

    public HotReloadPickBottomSheet(@NonNull Activity activity) {
        super(activity);
        mActivity = activity;
        initBehavior(activity);
    }

    private void initBehavior(Activity activity) {
        setDragHandleViewEnabled(true);
        BottomSheetBehavior<FrameLayout> behavior = getBehavior();
        // 直接以展开状态出现，跳过半展开/折叠状态，避免"刚弹出就缩到很小一条"的视觉错觉。
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        // 关闭整张 sheet 的拖动手势：否则 BottomSheet 会在 RecyclerView 上抢占触摸事件
        // 作为"下拉收起"的手势检测，导致 item click 不响应。用户仍可通过 drag handle
        // 或下拉手势在 handle 区域收起。
        behavior.setDraggable(false);
        behavior.setSkipHalfExpanded(true);
        behavior.setSkipCollapsed(true);
        behavior.setForceFullHeight(false);
        behavior.setPeekHeight(-1);

        setCanceledOnTouchOutside(true);
        if (HyperMaterialUtils.isFeatureEnable(activity) && RomUtils.getHyperOsVersion() >= 2) {
            behavior.setModeConfig(0);
            applyBlur(true);
        }
    }

    public HotReloadPickBottomSheet setOnTargetPickedListener(OnTargetPickedListener l) {
        this.mListener = l;
        return this;
    }

    public void showTargets(@NonNull List<HookedTarget> targets) {
        View view = LayoutInflater.from(mActivity)
            .inflate(R.layout.bottom_sheet_hot_reload_pick, null);

        RecyclerView list = view.findViewById(R.id.hot_reload_list);
        list.setLayoutManager(new LinearLayoutManager(mActivity));
        list.setAdapter(new TargetAdapter(targets, target -> {
            if (mListener != null) mListener.onTargetPicked(target);
            dismiss();
        }));

        setContentView(view);
        show();
    }

    public interface OnTargetPickedListener {
        void onTargetPicked(@NonNull HookedTarget target);
    }

    // ---- Adapter ----

    private static final class TargetAdapter extends RecyclerView.Adapter<TargetViewHolder> {
        private final List<HookedTarget> mItems;
        private final OnTargetPickedListener mClick;

        TargetAdapter(List<HookedTarget> items, OnTargetPickedListener click) {
            this.mItems = items;
            this.mClick = click;
        }

        @NonNull
        @Override
        public TargetViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_hot_reload_target, parent, false);
            return new TargetViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull TargetViewHolder holder, int position) {
            HookedTarget t = mItems.get(position);
            holder.bind(t, mClick);
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }
    }

    private static final class TargetViewHolder extends RecyclerView.ViewHolder {
        private final TextView mProcessName;
        private final TextView mMeta;
        private final TextView mStateBadge;

        TargetViewHolder(@NonNull View itemView) {
            super(itemView);
            mProcessName = itemView.findViewById(R.id.target_process_name);
            mMeta = itemView.findViewById(R.id.target_meta);
            mStateBadge = itemView.findViewById(R.id.target_state_badge);
        }

        void bind(@NonNull HookedTarget target, @NonNull OnTargetPickedListener click) {
            mProcessName.setText(target.getProcessName());
            mMeta.setText(itemView.getContext().getString(
                string.settings_hot_reload_target_meta,
                target.getPid(),
                target.getUid(),
                target.getLoadedVersionCode()
            ));
            mStateBadge.setText(target.getState().name());
            mStateBadge.getBackground().mutate().setTint(
                ContextCompat.getColor(itemView.getContext(), getStateColor(target.getState()))
            );
            mStateBadge.setTextColor(
                ContextCompat.getColor(itemView.getContext(), getStateTextColor(target.getState()))
            );
            itemView.setOnClickListener(v -> click.onTargetPicked(target));
        }

        private static int getStateColor(HookedTarget.State state) {
            // 复用 log level 徽章颜色：UP_TO_DATE→info, STALE→warn, RELOADING→debug, FAILED→error
            return switch (state) {
                case UP_TO_DATE -> com.sevtinge.hyperceiler.R.color.log_level_badge_bg_info;
                case STALE -> com.sevtinge.hyperceiler.R.color.log_level_badge_bg_warn;
                case RELOADING -> com.sevtinge.hyperceiler.R.color.log_level_badge_bg_debug;
                case FAILED -> com.sevtinge.hyperceiler.R.color.log_level_badge_bg_error;
            };
        }

        private static int getStateTextColor(HookedTarget.State state) {
            return switch (state) {
                case UP_TO_DATE -> com.sevtinge.hyperceiler.R.color.log_level_badge_text_info;
                case STALE -> com.sevtinge.hyperceiler.R.color.log_level_badge_text_warn;
                case RELOADING -> com.sevtinge.hyperceiler.R.color.log_level_badge_text_debug;
                case FAILED -> com.sevtinge.hyperceiler.R.color.log_level_badge_text_error;
            };
        }
    }
}
