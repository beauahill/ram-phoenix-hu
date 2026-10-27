package com.ramphoenix.launcher;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;

/** Read-only, signature-protected sharing with Phoenix Camera. No arbitrary file paths. */
public final class AppearanceProvider extends ContentProvider {
    @Override public boolean onCreate() {return true;}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        if(!"/settings".equals(uri.getPath()))throw new IllegalArgumentException("Appearance resource");
        File image=AppearanceStore.image(getContext());
        MatrixCursor cursor=new MatrixCursor(new String[]{"dim","softness","version","custom"});
        cursor.addRow(new Object[]{AppearanceStore.dim(getContext()),AppearanceStore.softness(getContext()),
                image.lastModified(),image.isFile()?1:0});return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"/wallpaper".equals(uri.getPath()) || !"r".equals(mode))throw new FileNotFoundException("Read-only wallpaper");
        return ParcelFileDescriptor.open(AppearanceStore.image(getContext()),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) {return "/wallpaper".equals(uri.getPath())?"image/webp":"vnd.android.cursor.item/vnd.phoenix.appearance";}
    @Override public Uri insert(Uri uri,ContentValues values) {throw new UnsupportedOperationException("Read only");}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args) {throw new UnsupportedOperationException("Read only");}
    @Override public int delete(Uri uri,String where,String[] args) {throw new UnsupportedOperationException("Read only");}
}
