package com.oilquiz.app.repository;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.oilquiz.app.model.ImportHistory;
import java.util.ArrayList;
import java.util.List;

public class ImportHistoryRepository {
    private static final int MAX_HISTORY_ITEMS = 50;
    private static final String PREF_NAME = "import_history_pref";
    private static final String KEY_HISTORY_LIST = "history_list";
    
    private SharedPreferences sharedPreferences;
    private Gson gson;

    public ImportHistoryRepository(Application application) {
        sharedPreferences = application.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
    }

    public void addImportHistory(ImportHistory importHistory) {
        List<ImportHistory> historyList = getImportHistory();
        
        // 设置ID
        importHistory.setId(System.currentTimeMillis());
        
        // 添加到开头
        historyList.add(0, importHistory);
        
        // 限制历史记录数量
        if (historyList.size() > MAX_HISTORY_ITEMS) {
            historyList = historyList.subList(0, MAX_HISTORY_ITEMS);
        }
        
        // 保存到SharedPreferences
        saveHistoryList(historyList);
    }

    public List<ImportHistory> getImportHistory() {
        String json = sharedPreferences.getString(KEY_HISTORY_LIST, null);
        if (json == null) {
            return new ArrayList<>();
        }
        
        try {
            return gson.fromJson(json, new TypeToken<List<ImportHistory>>(){}.getType());
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public void clearImportHistory() {
        sharedPreferences.edit().remove(KEY_HISTORY_LIST).apply();
    }
    
    public void deleteImportHistory(long id) {
        List<ImportHistory> historyList = getImportHistory();
        historyList.removeIf(history -> history.getId() == id);
        saveHistoryList(historyList);
    }
    
    private void saveHistoryList(List<ImportHistory> historyList) {
        String json = gson.toJson(historyList);
        sharedPreferences.edit().putString(KEY_HISTORY_LIST, json).apply();
    }
}
