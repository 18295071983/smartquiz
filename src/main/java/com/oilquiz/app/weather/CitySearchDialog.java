package com.oilquiz.app.weather;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;

import java.util.ArrayList;
import java.util.List;

public class CitySearchDialog extends Dialog {

    public interface OnCitySelectedListener {
        void onCitySelected(QWeatherCityManager.CityEntry city);
    }

    private static final String PREFS_NAME = "city_search_history";
    private static final String KEY_HISTORY = "history_cities";
    private static final int MAX_HISTORY = 8;
    private static final int CHIPS_PER_ROW = 4;

    private final Context context;
    private OnCitySelectedListener listener;
    private QWeatherCityManager cityManager;
    private SharedPreferences prefs;

    private EditText etSearch;
    private ImageView btnClear;
    private TextView tvHotCitiesTitle;
    private TextView tvSearchResultsTitle;
    private TextView tvNoResults;
    private TextView tvNoHistory;
    private ScrollView svHistory;
    private LinearLayout llHistoryContainer;
    private RecyclerView rvSearchResults;

    private List<QWeatherCityManager.CityEntry> searchResults = new ArrayList<>();
    private CityResultAdapter adapter;

    public CitySearchDialog(@NonNull Context context) {
        super(context);
        this.context = context;
        init();
    }

    public CitySearchDialog(@NonNull Context context, OnCitySelectedListener listener) {
        super(context);
        this.context = context;
        this.listener = listener;
        init();
    }

    private void init() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_city_search, null);
        setContentView(view);

        Window window = getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawableResource(R.drawable.bg_dialog_dark);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        cityManager = QWeatherCityManager.getInstance(context);

        etSearch = findViewById(R.id.et_city_search);
        btnClear = findViewById(R.id.btn_search_clear);
        tvHotCitiesTitle = findViewById(R.id.tv_hot_cities_title);
        tvSearchResultsTitle = findViewById(R.id.tv_search_results_title);
        tvNoResults = findViewById(R.id.tv_no_results);
        tvNoHistory = findViewById(R.id.tv_no_history);
        svHistory = findViewById(R.id.sv_history);
        llHistoryContainer = findViewById(R.id.ll_history_container);
        rvSearchResults = findViewById(R.id.rv_search_results);

        setupSearchFunctionality();
        setupHistoryCities();
        setupRecyclerView();
    }

    private void setupSearchFunctionality() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim();
                btnClear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                if (query.isEmpty()) {
                    showHistoryCities();
                } else {
                    performSearch(query);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnClear.setOnClickListener(v -> {
            etSearch.setText("");
            etSearch.requestFocus();
        });
    }

    private void setupHistoryCities() {
        List<String> history = getHistoryCities();

        llHistoryContainer.removeAllViews();

        if (history.isEmpty()) {
            tvNoHistory.setVisibility(View.VISIBLE);
            svHistory.setVisibility(View.GONE);
        } else {
            tvNoHistory.setVisibility(View.GONE);
            svHistory.setVisibility(View.VISIBLE);
            buildHistoryChips(history);
        }
        tvHotCitiesTitle.setText("历史查询");
    }

    private void buildHistoryChips(List<String> history) {
        llHistoryContainer.setOrientation(LinearLayout.VERTICAL);
        llHistoryContainer.setWeightSum(1f);

        int rowCount = (history.size() + CHIPS_PER_ROW - 1) / CHIPS_PER_ROW;
        for (int row = 0; row < rowCount; row++) {
            LinearLayout rowLayout = new LinearLayout(context);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setWeightSum(CHIPS_PER_ROW);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (row > 0) {
                rowParams.topMargin = dpToPx(8);
            }
            rowLayout.setLayoutParams(rowParams);

            for (int col = 0; col < CHIPS_PER_ROW; col++) {
                int index = row * CHIPS_PER_ROW + col;
                if (index < history.size()) {
                    final String cityName = history.get(index);
                    TextView chip = createCityChip(cityName);
                    chip.setOnClickListener(v -> onHistoryCityClicked(cityName));
                    LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    if (col > 0) {
                        chipParams.leftMargin = dpToPx(8);
                    }
                    chip.setLayoutParams(chipParams);
                    rowLayout.addView(chip);
                } else {
                    View spacer = new View(context);
                    LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    if (col > 0) {
                        spacerParams.leftMargin = dpToPx(8);
                    }
                    spacer.setLayoutParams(spacerParams);
                    rowLayout.addView(spacer);
                }
            }
            llHistoryContainer.addView(rowLayout);
        }
    }

    private TextView createCityChip(String cityName) {
        TextView chip = new TextView(context);
        chip.setText(cityName);
        chip.setTextColor(context.getColor(R.color.weather_dark_text));
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setGravity(Gravity.CENTER);
        chip.setBackgroundResource(R.drawable.bg_city_chip);
        chip.setMinHeight(dpToPx(40));
        chip.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        chip.setClickable(true);
        chip.setFocusable(true);
        return chip;
    }

    private void onHistoryCityClicked(String cityName) {
        QWeatherCityManager.CityEntry city = cityManager.getCityByName(cityName);
        if (city != null) {
            selectCity(city);
        } else {
            QWeatherCityManager.CityEntry entry = new QWeatherCityManager.CityEntry();
            entry.nameZh = cityName;
            entry.locationId = cityName;
            selectCity(entry);
        }
    }

    private void showHistoryCities() {
        setupHistoryCities();
        tvHotCitiesTitle.setVisibility(View.VISIBLE);
        tvSearchResultsTitle.setVisibility(View.GONE);
        rvSearchResults.setVisibility(View.GONE);
        tvNoResults.setVisibility(View.GONE);
        searchResults.clear();
        adapter.notifyDataSetChanged();
    }

    private void setupRecyclerView() {
        rvSearchResults.setLayoutManager(new LinearLayoutManager(context));
        adapter = new CityResultAdapter(searchResults, this::selectCity);
        rvSearchResults.setAdapter(adapter);
    }

    private void performSearch(String query) {
        List<QWeatherCityManager.CityEntry> results = cityManager.searchCities(query);
        searchResults.clear();
        searchResults.addAll(results);
        adapter.notifyDataSetChanged();

        tvHotCitiesTitle.setVisibility(View.GONE);
        tvNoHistory.setVisibility(View.GONE);
        svHistory.setVisibility(View.GONE);
        tvSearchResultsTitle.setVisibility(View.VISIBLE);

        if (searchResults.isEmpty()) {
            rvSearchResults.setVisibility(View.GONE);
            tvNoResults.setVisibility(View.VISIBLE);
        } else {
            rvSearchResults.setVisibility(View.VISIBLE);
            tvNoResults.setVisibility(View.GONE);
        }
    }

    private void selectCity(QWeatherCityManager.CityEntry city) {
        if (city != null && city.nameZh != null) {
            saveCityToHistory(city.nameZh);
        }
        if (listener != null) {
            listener.onCitySelected(city);
        }
        dismiss();
    }

    private void saveCityToHistory(String cityName) {
        List<String> history = getHistoryCities();
        history.remove(cityName);
        history.add(0, cityName);
        while (history.size() > MAX_HISTORY) {
            history.remove(history.size() - 1);
        }
        prefs.edit().putString(KEY_HISTORY, TextUtils.join(",", history)).apply();
    }

    private List<String> getHistoryCities() {
        List<String> history = new ArrayList<>();
        String saved = prefs.getString(KEY_HISTORY, "");
        if (saved != null && !saved.isEmpty()) {
            String[] parts = saved.split(",");
            for (String part : parts) {
                if (part != null && !part.trim().isEmpty()) {
                    history.add(part.trim());
                }
            }
        }
        return history;
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                context.getResources().getDisplayMetrics());
    }

    public void setOnCitySelectedListener(OnCitySelectedListener listener) {
        this.listener = listener;
    }

    private static class CityResultAdapter extends RecyclerView.Adapter<CityResultAdapter.CityViewHolder> {

        private List<QWeatherCityManager.CityEntry> cities;
        private OnCitySelectedListener clickListener;

        interface OnCitySelectedListener {
            void onCitySelected(QWeatherCityManager.CityEntry city);
        }

        CityResultAdapter(List<QWeatherCityManager.CityEntry> cities, OnCitySelectedListener listener) {
            this.cities = cities;
            this.clickListener = listener;
        }

        @NonNull
        @Override
        public CityViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_city_result, parent, false);
            return new CityViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull CityViewHolder holder, int position) {
            QWeatherCityManager.CityEntry city = cities.get(position);
            holder.tvName.setText(city.nameZh != null ? city.nameZh : "");
            holder.tvLocation.setText(city.getFullName());

            holder.itemView.setOnClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onCitySelected(city);
                }
            });
        }

        @Override
        public int getItemCount() {
            return cities.size();
        }

        static class CityViewHolder extends RecyclerView.ViewHolder {
            TextView tvName;
            TextView tvLocation;

            CityViewHolder(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tv_city_name);
                tvLocation = itemView.findViewById(R.id.tv_city_location);
            }
        }
    }
}
