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

/** Horizontal adapter for the curated quick-launch tile row at the top. */
public class TileAdapter extends RecyclerView.Adapter<TileAdapter.VH> {

    public interface Listener {
        void onLaunch(AppInfo app);
    }

    private final Context ctx;
    private final List<AppInfo> tiles;
    private final Listener listener;

    public TileAdapter(Context ctx, List<AppInfo> tiles, Listener listener) {
        this.ctx = ctx;
        this.tiles = tiles;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_tile, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final AppInfo app = tiles.get(position);
        h.label.setText(app.label);
        try {
            Drawable icon = ctx.getPackageManager().getActivityIcon(app.component);
            h.icon.setImageDrawable(icon);
        } catch (Exception e) {
            h.icon.setImageResource(R.drawable.ic_app_fallback);
        }
        h.itemView.setOnClickListener(v -> listener.onLaunch(app));
    }

    @Override
    public int getItemCount() {
        return tiles.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        VH(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.tile_icon);
            label = v.findViewById(R.id.tile_label);
        }
    }
}
