/*
 * This file is part of HyperCeiler.

 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.common.model.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.collection.LruCache;
import androidx.recyclerview.widget.RecyclerView;

import com.sevtinge.hyperceiler.common.model.data.ModData;
import com.sevtinge.hyperceiler.common.utils.SearchIndexManager;
import com.sevtinge.hyperceiler.core.R;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fan.recyclerview.card.CardGroupAdapter;

public class ModSearchAdapter extends CardGroupAdapter {

    private static final int TYPE_ITEM = 0;
    private static final int TYPE_EMPTY = 1;

    private final List<ModData> mItems = new ArrayList<>();
    private final List<Integer> mGroupIndices = new ArrayList<>();
    private String mQuery = "";
    private boolean mIsChinese = false;
    private boolean mIsEmpty = false;
    private OnItemClickListener mClickListener;

    private static final LruCache<String, Drawable> sIconCache = new LruCache<>(64);
    private static final Map<String, String> sGroupToPackage = new LinkedHashMap<>();
    private final List<Boolean> mIsGroupFirst = new ArrayList<>();
    private static final Map<String, Integer> SPECIAL_ICONS = Map.of(
        "system", R.drawable.ic_system_framework_new
    );

    public interface OnItemClickListener {
        void onItemClick(View view, ModData data);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        mClickListener = listener;
    }

    public static void initGroupPackageMap(Map<String, String> map) {
        sGroupToPackage.clear();
        sGroupToPackage.putAll(map);
    }

    private static Drawable getAppIcon(Context context, String groupName) {
        Drawable cached = sIconCache.get(groupName);
        if (cached != null) return cached;

        String packageName = sGroupToPackage.get(groupName);
        if (packageName != null) {
            Integer specialIcon = SPECIAL_ICONS.get(packageName);
            if (specialIcon != null) {
                try {
                    Drawable icon = context.getDrawable(specialIcon);
                    if (icon != null) {
                        sIconCache.put(groupName, icon);
                        return icon;
                    }
                } catch (Exception ignored) {}
            }

            // 正常包名
            try {
                PackageManager pm = context.getPackageManager();
                ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                Drawable icon = info.loadIcon(pm);
                sIconCache.put(groupName, icon);
                return icon;
            } catch (PackageManager.NameNotFoundException ignored) {}
        }

        return null;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void submitSearch(Context context, String query) {
        mQuery = query == null ? "" : query;
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        mIsChinese = locale.getLanguage().equals(new Locale("zh").getLanguage());

        mItems.clear();
        mGroupIndices.clear();
        mIsGroupFirst.clear();

        if (!mQuery.isEmpty()) {
            List<ModData> results = SearchIndexManager.search(mQuery, locale);
            if (!results.isEmpty()) {
                LinkedHashMap<String, List<ModData>> groups = new LinkedHashMap<>();
                for (ModData mod : results) {
                    groups.computeIfAbsent(mod.getGroup(), k -> new ArrayList<>()).add(mod);
                }
                int groupIdx = 0;
                for (Map.Entry<String, List<ModData>> entry : groups.entrySet()) {
                    boolean first = true;
                    for (ModData mod : entry.getValue()) {
                        mItems.add(mod);
                        mGroupIndices.add(groupIdx);
                        mIsGroupFirst.add(first);
                        first = false;
                    }
                    groupIdx++;
                }
            }
        }

        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public int getItemViewType(int position) {
        return TYPE_ITEM;
    }

    @Override
    public int getItemViewGroup(int position) {
        if (mIsEmpty) return 0;
        return position < mGroupIndices.size() ? mGroupIndices.get(position) : 0;
    }

    @Override
    public void setHasStableIds() {}

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_EMPTY) {
            return new EmptyHolder(inflater.inflate(R.layout.item_search_empty, parent, false));
        }
        return new ItemHolder(inflater.inflate(R.layout.item_search_result, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        super.onBindViewHolder(holder, position);

        if (holder instanceof ItemHolder ih) {
            ModData mod = mItems.get(position);
            boolean isFirst = mIsGroupFirst.get(position);

            if (isFirst) {
                Drawable icon = getAppIcon(ih.itemView.getContext(), mod.getGroup());
                if (icon != null) {
                    ih.icon.setImageDrawable(icon);
                } else {
                    ih.icon.setImageResource(R.drawable.ic_various_new);
                }
                ih.icon.setVisibility(View.VISIBLE);
            } else {
                ih.icon.setVisibility(View.INVISIBLE);
            }

            ih.bind(mod, mIsChinese, mQuery);
            ih.itemView.setOnClickListener(v -> {
                if (mClickListener != null) mClickListener.onItemClick(v, mod);
            });
        }
    }

    static class ItemHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView summary;

        ItemHolder(View v) {
            super(v);
            icon = v.findViewById(android.R.id.icon);
            title = v.findViewById(android.R.id.title);
            summary = v.findViewById(android.R.id.summary);
        }

        void bind(ModData mod, boolean isChinese, String query) {
            String lowerQuery = query.toLowerCase();
            Spannable spannable = new SpannableString(mod.title);

            if (isChinese) {
                for (int i = 0; i < lowerQuery.length(); i++) {
                    String ch = String.valueOf(lowerQuery.charAt(i));
                    int start = mod.title.toLowerCase().indexOf(ch);
                    if (start >= 0) {
                        spannable.setSpan(new ForegroundColorSpan(SearchIndexManager.MARK_COLOR),
                            start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
            } else {
                int start = mod.title.toLowerCase().indexOf(lowerQuery);
                if (start >= 0) {
                    spannable.setSpan(new ForegroundColorSpan(SearchIndexManager.MARK_COLOR),
                        start, start + lowerQuery.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }

            title.setText(spannable, TextView.BufferType.SPANNABLE);

            // ★ 只有多层路径才显示
            boolean hasMultiLevel = mod.breadcrumbs != null && mod.breadcrumbs.contains("/");
            if (hasMultiLevel) {
                summary.setText(mod.breadcrumbs);
                summary.setVisibility(View.VISIBLE);
            } else {
                summary.setVisibility(View.GONE);
            }
        }
    }

    static class EmptyHolder extends RecyclerView.ViewHolder {
        EmptyHolder(View v) { super(v); }
    }
}
