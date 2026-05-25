package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.oilquiz.app.R;
import com.oilquiz.app.util.AIIconManager;
import com.oilquiz.app.util.AIIconMapper;
import com.oilquiz.app.util.AIIconMapper.AIIconCategory;
import com.oilquiz.app.util.AIIconMapper.AIIconInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AIIconDemoActivity extends AppCompatActivity {

    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private EditText etIntentTest;
    private android.widget.Button btnTestIntent;
    private LinearLayout llTestResult;
    private ImageView ivTestResult;
    private TextView tvTestIconName;
    private TextView tvTestIconDesc;
    private TextView tvQuick1, tvQuick2, tvQuick3, tvQuick4;
    private ImageView btnBack;

    private final String[] tabTitles = {"核心图标", "功能图标", "工具图标", "状态图标", "聊天图标"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_icon_demo);

        initViews();
        setupViewPager();
        setupTestSection();
    }

    private void initViews() {
        tabLayout = findViewById(R.id.tabLayout);
        viewPager = findViewById(R.id.viewPager);
        etIntentTest = findViewById(R.id.etIntentTest);
        btnTestIntent = findViewById(R.id.btnTestIntent);
        llTestResult = findViewById(R.id.llTestResult);
        ivTestResult = findViewById(R.id.ivTestResult);
        tvTestIconName = findViewById(R.id.tvTestIconName);
        tvTestIconDesc = findViewById(R.id.tvTestIconDesc);
        tvQuick1 = findViewById(R.id.tvQuick1);
        tvQuick2 = findViewById(R.id.tvQuick2);
        tvQuick3 = findViewById(R.id.tvQuick3);
        tvQuick4 = findViewById(R.id.tvQuick4);
        btnBack = findViewById(R.id.btnBack);

        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void setupViewPager() {
        IconPagerAdapter adapter = new IconPagerAdapter(this);
        viewPager.setAdapter(adapter);

        new TabLayoutMediator(tabLayout, viewPager,
                new TabLayoutMediator.TabConfigurationStrategy() {
                    @Override
                    public void onConfigureTab(@NonNull TabLayout.Tab tab, int position) {
                        tab.setText(tabTitles[position]);
                    }
                }).attach();
    }

    private void setupTestSection() {
        btnTestIntent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testIntentIcon();
            }
        });

        etIntentTest.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() > 0) {
                    testIntentIcon();
                } else {
                    llTestResult.setVisibility(View.GONE);
                }
            }
        });

        View.OnClickListener quickClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TextView tv = (TextView) v;
                etIntentTest.setText(tv.getText().toString());
                etIntentTest.setSelection(etIntentTest.getText().length());
            }
        };

        tvQuick1.setOnClickListener(quickClickListener);
        tvQuick2.setOnClickListener(quickClickListener);
        tvQuick3.setOnClickListener(quickClickListener);
        tvQuick4.setOnClickListener(quickClickListener);
    }

    private void testIntentIcon() {
        String intentType = etIntentTest.getText().toString().trim().toLowerCase();
        if (intentType.isEmpty()) {
            llTestResult.setVisibility(View.GONE);
            return;
        }

        int iconResId = AIIconMapper.getIconForIntent(intentType);
        AIIconInfo iconInfo = findIconInfoByResourceId(iconResId);

        if (iconInfo != null) {
            llTestResult.setVisibility(View.VISIBLE);
            ivTestResult.setImageResource(iconResId);
            tvTestIconName.setText(iconInfo.getDisplayName() + " (" + iconInfo.getKey() + ")");
            tvTestIconDesc.setText(iconInfo.getDescription());
        } else {
            llTestResult.setVisibility(View.VISIBLE);
            ivTestResult.setImageResource(iconResId);
            tvTestIconName.setText("默认图标");
            tvTestIconDesc.setText("未找到匹配的图标，使用默认机器人图标");
        }
    }

    private AIIconInfo findIconInfoByResourceId(int resId) {
        Map<String, AIIconInfo> allIcons = AIIconMapper.getAllIconInfo();
        for (AIIconInfo info : allIcons.values()) {
            if (info.getResourceId() == resId) {
                return info;
            }
        }
        return null;
    }

    private static class IconPagerAdapter extends FragmentStateAdapter {

        public IconPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
            super(fragmentActivity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            AIIconCategory category;
            switch (position) {
                case 0:
                    category = AIIconCategory.CORE;
                    break;
                case 1:
                    category = AIIconCategory.FUNCTION;
                    break;
                case 2:
                    category = AIIconCategory.TOOL;
                    break;
                case 3:
                    category = AIIconCategory.STATUS;
                    break;
                case 4:
                default:
                    category = AIIconCategory.CHAT;
                    break;
            }
            return IconGridFragment.newInstance(category);
        }

        @Override
        public int getItemCount() {
            return 5;
        }
    }

    public static class IconGridFragment extends Fragment {

        private static final String ARG_CATEGORY = "category";
        private AIIconCategory category;

        public static IconGridFragment newInstance(AIIconCategory category) {
            IconGridFragment fragment = new IconGridFragment();
            Bundle args = new Bundle();
            args.putSerializable(ARG_CATEGORY, category);
            fragment.setArguments(args);
            return fragment;
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            if (getArguments() != null) {
                category = (AIIconCategory) getArguments().getSerializable(ARG_CATEGORY);
            }
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = inflater.inflate(R.layout.item_icon_grid, container, false);
            RecyclerView recyclerView = view.findViewById(R.id.recyclerView);
            recyclerView.setLayoutManager(new GridLayoutManager(getContext(), 3));
            recyclerView.setAdapter(new IconAdapter(getIconsByCategory(category)));
            return view;
        }

        private List<AIIconInfo> getIconsByCategory(AIIconCategory category) {
            List<AIIconInfo> icons = new ArrayList<>();
            Map<String, AIIconInfo> allIcons = AIIconMapper.getAllIconInfo();
            for (AIIconInfo info : allIcons.values()) {
                if (info.getCategory() == category) {
                    icons.add(info);
                }
            }
            return icons;
        }

        private static class IconAdapter extends RecyclerView.Adapter<IconAdapter.IconViewHolder> {

            private final List<AIIconInfo> icons;

            public IconAdapter(List<AIIconInfo> icons) {
                this.icons = icons;
            }

            @NonNull
            @Override
            public IconViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_icon_card, parent, false);
                return new IconViewHolder(view);
            }

            @Override
            public void onBindViewHolder(@NonNull IconViewHolder holder, int position) {
                AIIconInfo icon = icons.get(position);
                holder.ivIcon.setImageResource(icon.getResourceId());
                holder.tvIconName.setText(icon.getDisplayName());
                holder.tvIconKey.setText(icon.getKey());

                holder.itemView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Toast.makeText(v.getContext(), 
                            "图标: " + icon.getDisplayName() + "\n描述: " + icon.getDescription(),
                            Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public int getItemCount() {
                return icons.size();
            }

            static class IconViewHolder extends RecyclerView.ViewHolder {
                ImageView ivIcon;
                TextView tvIconName;
                TextView tvIconKey;

                public IconViewHolder(@NonNull View itemView) {
                    super(itemView);
                    ivIcon = itemView.findViewById(R.id.ivIcon);
                    tvIconName = itemView.findViewById(R.id.tvIconName);
                    tvIconKey = itemView.findViewById(R.id.tvIconKey);
                }
            }
        }
    }
}
