package com.ramphoenix.launcher;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/** Grid adapter for the full app drawer. Loads icons lazily on bind. */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {

    public interface Listener {
        void onLaunch(AppInfo app);
        void onDetails(AppInfo app);
    }

    private final Context ctx;
    private final List<AppInfo> apps;
    private final Listener listener;

    public AppAdapter(Context ctx, List<AppInfo> apps, Listener listener) {
        this.ctx = ctx;
        this.apps = apps;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_app, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final AppInfo app = apps.get(position);
        h.label.setText(app.label);
        try {
            Drawable icon = ctx.getPackageManager().getActivityIcon(app.component);
            h.icon.setImageDrawable(icon);
        } catch (Exception e) {
            h.icon.setImageResource(R.drawable.ic_app_fallback);
        }
        h.itemView.setOnClickListener(v -> listener.onLaunch(app));
        h.itemView.setOnLongClickListener(v -> {
            listener.onDetails(app);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        VH(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.app_icon);
            label = v.findViewById(R.id.app_label);
        }
    }
}
