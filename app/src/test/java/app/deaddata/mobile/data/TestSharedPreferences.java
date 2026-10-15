package app.deaddata.mobile.data;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory {@link SharedPreferences} used by local unit tests.
 *
 * The android.jar shipped to unit tests is a stub, so account persistence is
 * verified against this small hand-written implementation instead of pulling in
 * Robolectric or AndroidX test dependencies.
 */
public final class TestSharedPreferences implements SharedPreferences {
    private final Map<String, Object> values = new HashMap<>();
    private final List<OnSharedPreferenceChangeListener> listeners = new ArrayList<>();

    @Override
    public Map<String, ?> getAll() {
        return new HashMap<>(values);
    }

    @Override
    public String getString(String key, String defValue) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : defValue;
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        Object value = values.get(key);
        if (value instanceof Set) {
            Set<?> stored = (Set<?>) value;
            Set<String> copy = new HashSet<>();
            for (Object item : stored) copy.add(String.valueOf(item));
            return copy;
        }
        return defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).intValue() : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).longValue() : defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).floatValue() : defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : defValue;
    }

    @Override
    public boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new EditorImpl();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
        listeners.add(listener);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
        listeners.remove(listener);
    }

    private void applyChanges(Map<String, Object> pending, Set<String> removed, boolean clear) {
        if (clear) values.clear();
        for (String key : removed) values.remove(key);
        values.putAll(pending);
        for (OnSharedPreferenceChangeListener listener : new ArrayList<>(listeners)) {
            if (listener != null) listener.onSharedPreferenceChanged(this, null);
        }
    }

    private final class EditorImpl implements Editor {
        private final Map<String, Object> pending = new HashMap<>();
        private final Set<String> removed = new HashSet<>();
        private boolean clear;

        @Override
        public Editor putString(String key, String value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public Editor putStringSet(String key, Set<String> value) {
            pending.put(key, value == null ? null : new HashSet<>(value));
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public Editor remove(String key) {
            removed.add(key);
            return this;
        }

        @Override
        public Editor clear() {
            clear = true;
            return this;
        }

        @Override
        public boolean commit() {
            applyChanges(pending, removed, clear);
            pending.clear();
            removed.clear();
            clear = false;
            return true;
        }

        @Override
        public void apply() {
            commit();
        }
    }
}
