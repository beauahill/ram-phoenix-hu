package com.ramphoenix.launcher;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

/** GridView adapter for the app drawer. Loads icons lazily on bind. */
public class AppGridAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<AppInfo> apps;
    private final LayoutInflater inflater;

    public AppGridAdapter(Context ctx, List<AppInfo> apps) {
        this.ctx = ctx;
        this.apps = apps;
        this.inflater = LayoutInflater.from(ctx);
    }

    @Override public int getCount() { return apps.size(); }
    @Override public Object getItem(int position) { return apps.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView != null ? convertView : inflater.inflate(R.layout.item_app, parent, false);
        AppInfo app = apps.get(position);
        ImageView icon = v.findViewById(R.id.app_icon);
        TextView label = v.findViewById(R.id.app_label);
        label.setText(app.label);
        try {
            Drawable d = ctx.getPackageManager().getActivityIcon(app.component);
            icon.setImageDrawable(d);
        } catch (Exception e) {
            icon.setImageResource(R.drawable.ic_app_fallback);
        }
        return v;
    }
}
