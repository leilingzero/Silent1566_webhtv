package com.fongmi.android.tv.ui.presenter;

import android.os.Build;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.bean.TmdbPerson;
import com.fongmi.android.tv.databinding.AdapterTmdbCastBinding;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.card.MaterialCardView;

public class TmdbCastPresenter extends Presenter {

    private static final int STROKE_NORMAL = 0x33FFFFFF;
    private static final int STROKE_FOCUSED = 0xFFFFFFFF;

    private final OnClickListener mListener;

    public TmdbCastPresenter(OnClickListener listener) {
        this.mListener = listener;
    }

    public interface OnClickListener {
        void onItemClick(TmdbPerson item);
    }

    @Override
    public Presenter.ViewHolder onCreateViewHolder(ViewGroup parent) {
        return new ViewHolder(AdapterTmdbCastBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(Presenter.ViewHolder viewHolder, Object item) {
        TmdbPerson person = (TmdbPerson) item;
        ViewHolder holder = (ViewHolder) viewHolder;
        bindFocusStyle(holder.binding.getRoot());
        holder.binding.name.setText(person.getName());
        holder.binding.role.setText(person.getSubtitle());
        if (!person.getProfileUrl().isEmpty()) {
            Glide.with(holder.binding.getRoot().getContext()).load(person.getProfileUrl()).into(holder.binding.profile);
        }
        setOnClickListener(holder, view -> {
            if (mListener != null) mListener.onItemClick(person);
        });
    }

    @Override
    public void onUnbindViewHolder(Presenter.ViewHolder viewHolder) {
    }

    private void bindFocusStyle(MaterialCardView card) {
        applyFocusStyle(card, card.hasFocus());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) card.setDefaultFocusHighlightEnabled(false);
        card.setOnFocusChangeListener((view, focused) -> applyFocusStyle(card, focused));
    }

    private void applyFocusStyle(MaterialCardView card, boolean focused) {
        card.setStrokeColor(focused ? STROKE_FOCUSED : STROKE_NORMAL);
        card.setStrokeWidth(ResUtil.dp2px(focused ? 3 : 1));
        card.setCardElevation(0);
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterTmdbCastBinding binding;

        public ViewHolder(@NonNull AdapterTmdbCastBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
