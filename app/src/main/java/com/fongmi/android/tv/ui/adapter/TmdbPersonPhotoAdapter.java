package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * TMDB 人物照片列表适配器
 */
public class TmdbPersonPhotoAdapter extends RecyclerView.Adapter<TmdbPersonPhotoAdapter.ViewHolder> {

    public interface Listener {
        void onItemClick(int position, String url);
    }

    private final List<String> items = new ArrayList<>();
    private final Listener listener;
    private boolean legacyMode;

    public TmdbPersonPhotoAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setLight(boolean light) {
        legacyMode = true;
        notifyDataSetChanged();
    }

    public void setItems(List<String> values) {
        items.clear();
        if (values != null) items.addAll(values);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = legacyMode ? R.layout.adapter_tmdb_person_photo : R.layout.item_tmdb_person_photo;
        View view = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new ViewHolder(view, legacyMode);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String url = items.get(position);
        ImgUtil.load(holder.photo.getContext().getString(R.string.detail_person_photos), url, holder.photo, true, 320, 480);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(position, url);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView photo;

        ViewHolder(View view, boolean legacyMode) {
            super(view);
            if (!legacyMode && !Util.isLeanback()) {
                itemView.setFocusable(false);
                itemView.setFocusableInTouchMode(false);
            }
            photo = view.findViewById(R.id.photo);
        }
    }
}
