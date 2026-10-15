package android.database;

import java.io.Closeable;

/**
 * Desktop stand-in for Android's Cursor: the part the shared data layer uses. In Java, as on
 * Android, so Kotlin sees the same platform types (getString may return null).
 */
public interface Cursor extends Closeable {
    int getCount();
    int getPosition();
    int getColumnCount();
    String[] getColumnNames();
    boolean isAfterLast();
    boolean moveToNext();
    boolean moveToFirst();
    boolean moveToPosition(int position);
    int getColumnIndex(String name);
    int getColumnIndexOrThrow(String name);
    String getColumnName(int index);
    boolean isNull(int index);
    String getString(int index);
    long getLong(int index);
    int getInt(int index);
    double getDouble(int index);
    float getFloat(int index);
    byte[] getBlob(int index);
    @Override void close();
}
