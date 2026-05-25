package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.mcp.MCPManager;
import com.oilquiz.app.ai.mcp.MCPServer;
import com.oilquiz.app.ai.mcp.MCPTool;
import com.oilquiz.app.ui.base.BaseActivity;
import com.oilquiz.app.util.AILogger;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MCP 服务器管理界面
 */
public class MCPManagerActivity extends BaseActivity {
    private static final String TAG = "MCPManagerActivity";

    private RecyclerView recyclerView;
    private MCPServerAdapter adapter;
    private View emptyView;
    private TextView tvServerCount;
    private TextView tvToolCount;

    private MCPManager mcpManager;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_mcp_manager;
    }

    @Override
    protected void initView() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("MCP 服务器管理");
        }

        recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MCPServerAdapter();
        recyclerView.setAdapter(adapter);

        emptyView = findViewById(R.id.empty_view);
        tvServerCount = findViewById(R.id.tv_server_count);
        tvToolCount = findViewById(R.id.tv_tool_count);

        FloatingActionButton fabAdd = findViewById(R.id.fab_add);
        fabAdd.setOnClickListener(v -> showAddServerDialog());
    }

    @Override
    protected void initData() {
        mcpManager = MCPManager.getInstance(this);
        // 先添加监听器，再加载保存的服务器
        mcpManager.addListener(new MCPManager.MCPStateListener() {
            @Override
            public void onServerConnected(MCPServer server) {
                runOnUiThread(() -> {
                    loadServers();
                    showToast("服务器已连接: " + server.getServerName());
                });
            }

            @Override
            public void onServerDisconnected(MCPServer server) {
                runOnUiThread(() -> loadServers());
            }

            @Override
            public void onResourcesUpdated(MCPServer server, JSONObject params) {
                runOnUiThread(() -> loadServers());
            }

            @Override
            public void onProgress(MCPServer server, JSONObject params) {
                // 显示进度
            }
        });
        // 加载保存的服务器
        mcpManager.loadSavedServers();
        loadServers();
    }

    @Override
    protected void initListener() {
        // 监听器已在 initData 中设置
    }

    private void loadServers() {
        List<MCPServer> servers = mcpManager.getAllServers();
        adapter.setServers(servers);

        int connectedCount = 0;
        int totalTools = 0;
        for (MCPServer server : servers) {
            if (server.isConnected()) {
                connectedCount++;
                totalTools += server.getAvailableTools().size();
            }
        }

        tvServerCount.setText(String.format("服务器: %d/%d", connectedCount, servers.size()));
        tvToolCount.setText(String.format("可用工具: %d", totalTools));

        emptyView.setVisibility(servers.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(servers.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showAddServerDialog() {
        String[] options = {"文件系统", "SQLite", "Git", "自定义命令"};

        new AlertDialog.Builder(this)
            .setTitle("添加 MCP 服务器")
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0:
                        showFilesystemDialog();
                        break;
                    case 1:
                        showSQLiteDialog();
                        break;
                    case 2:
                        showGitDialog();
                        break;
                    case 3:
                        showCustomCommandDialog();
                        break;
                }
            })
            .show();
    }

    private void showFilesystemDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_mcp_filesystem, null);
        TextInputEditText etName = view.findViewById(R.id.et_name);
        TextInputEditText etPath = view.findViewById(R.id.et_path);

        etPath.setText(getFilesDir().getAbsolutePath());

        // 检查Termux环境
        boolean termuxInstalled = MCPManager.isTermuxInstalled();
        boolean npxAvailable = MCPManager.isTermuxCommandAvailable("npx");

        String title = "添加文件系统服务器";
        String message = null;
        if (!termuxInstalled) {
            message = "警告：未检测到Termux环境。请在F-Droid或Google Play安装Termux，然后运行：\n\npkg install nodejs\nnpm install -g npx";
        } else if (!npxAvailable) {
            message = "警告：Termux已安装但未找到npx。请在Termux中运行：\n\npkg install nodejs\nnpm install -g npx";
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view);

        if (message != null) {
            builder.setMessage(message);
        }

        builder.setPositiveButton("添加", (dialog, which) -> {
                String name = etName.getText().toString().trim();
                String path = etPath.getText().toString().trim();

                if (name.isEmpty() || path.isEmpty()) {
                    showToast("请填写完整信息");
                    return;
                }

                // 根据环境选择配置方式
                MCPServer.ServerConfig config;
                if (termuxInstalled && npxAvailable) {
                    config = MCPManager.createFilesystemConfigTermux(path);
                } else {
                    config = MCPManager.createFilesystemConfig(path);
                }
                addServer(name, config);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showSQLiteDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_mcp_sqlite, null);
        TextInputEditText etName = view.findViewById(R.id.et_name);
        TextInputEditText etPath = view.findViewById(R.id.et_path);

        etPath.setText(getDatabasePath("mcp.db").getAbsolutePath());

        // 检查Termux环境
        boolean termuxInstalled = MCPManager.isTermuxInstalled();
        boolean uvxAvailable = MCPManager.isTermuxCommandAvailable("uvx");

        String title = "添加 SQLite 服务器";
        String message = null;
        if (!termuxInstalled) {
            message = "警告：未检测到Termux环境。请在F-Droid或Google Play安装Termux，然后运行：\n\npkg install python\npip install uvx";
        } else if (!uvxAvailable) {
            message = "警告：Termux已安装但未找到uvx。请在Termux中运行：\n\npkg install python\npip install uvx";
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view);

        if (message != null) {
            builder.setMessage(message);
        }

        builder.setPositiveButton("添加", (dialog, which) -> {
                String name = etName.getText().toString().trim();
                String path = etPath.getText().toString().trim();

                if (name.isEmpty() || path.isEmpty()) {
                    showToast("请填写完整信息");
                    return;
                }

                // 根据环境选择配置方式
                MCPServer.ServerConfig config;
                if (termuxInstalled && uvxAvailable) {
                    config = MCPManager.createSQLiteConfigTermux(path);
                } else {
                    config = MCPManager.createSQLiteConfig(path);
                }
                addServer(name, config);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showGitDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_mcp_git, null);
        TextInputEditText etName = view.findViewById(R.id.et_name);
        TextInputEditText etPath = view.findViewById(R.id.et_path);

        etPath.setText(getFilesDir().getParent());

        // 检查Termux环境
        boolean termuxInstalled = MCPManager.isTermuxInstalled();
        boolean uvxAvailable = MCPManager.isTermuxCommandAvailable("uvx");

        String title = "添加 Git 服务器";
        String message = null;
        if (!termuxInstalled) {
            message = "警告：未检测到Termux环境。请在F-Droid或Google Play安装Termux，然后运行：\n\npkg install python\npip install uvx";
        } else if (!uvxAvailable) {
            message = "警告：Termux已安装但未找到uvx。请在Termux中运行：\n\npkg install python\npip install uvx";
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view);

        if (message != null) {
            builder.setMessage(message);
        }

        builder.setPositiveButton("添加", (dialog, which) -> {
                String name = etName.getText().toString().trim();
                String path = etPath.getText().toString().trim();

                if (name.isEmpty() || path.isEmpty()) {
                    showToast("请填写完整信息");
                    return;
                }

                // 根据环境选择配置方式
                MCPServer.ServerConfig config;
                if (termuxInstalled && uvxAvailable) {
                    config = MCPManager.createGitConfigTermux(path);
                } else {
                    config = MCPManager.createGitConfig(path);
                }
                addServer(name, config);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showCustomCommandDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_mcp_custom, null);
        TextInputEditText etName = view.findViewById(R.id.et_name);
        TextInputEditText etCommand = view.findViewById(R.id.et_command);

        new AlertDialog.Builder(this)
            .setTitle("添加自定义服务器")
            .setView(view)
            .setPositiveButton("添加", (dialog, which) -> {
                String name = etName.getText().toString().trim();
                String commandStr = etCommand.getText().toString().trim();

                if (name.isEmpty() || commandStr.isEmpty()) {
                    showToast("请填写完整信息");
                    return;
                }

                String[] parts = commandStr.split("\\s+");
                MCPServer.ServerConfig config = MCPManager.createCustomConfig(parts);
                addServer(name, config);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void addServer(String name, MCPServer.ServerConfig config) {
        showToast("正在连接服务器...");

        mcpManager.addServer(name, config)
            .thenAccept(success -> runOnUiThread(() -> {
                if (success) {
                    loadServers();
                    showToast("服务器添加成功");
                } else {
                    showToast("服务器连接失败");
                }
            }));
    }

    private void showServerTools(MCPServer server) {
        List<MCPTool> tools = server.getAvailableTools();
        if (tools.isEmpty()) {
            showToast("该服务器没有可用工具");
            return;
        }

        String[] toolNames = new String[tools.size()];
        for (int i = 0; i < tools.size(); i++) {
            toolNames[i] = tools.get(i).getName() + " - " + tools.get(i).getDescription();
        }

        new AlertDialog.Builder(this)
            .setTitle(server.getServerName() + " 的工具")
            .setItems(toolNames, null)
            .setPositiveButton("确定", null)
            .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_mcp_manager, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else if (item.getItemId() == R.id.action_refresh) {
            loadServers();
            return true;
        } else if (item.getItemId() == R.id.action_disconnect_all) {
            mcpManager.disconnectAll();
            loadServers();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // RecyclerView Adapter

    private class MCPServerAdapter extends RecyclerView.Adapter<MCPServerAdapter.ViewHolder> {
        private List<MCPServer> servers = new ArrayList<>();

        public void setServers(List<MCPServer> servers) {
            this.servers = servers;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mcp_server, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            MCPServer server = servers.get(position);
            holder.bind(server);
        }

        @Override
        public int getItemCount() {
            return servers.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            private final TextView tvName;
            private final TextView tvStatus;
            private final TextView tvTools;
            private final ImageView ivStatus;
            private final MaterialButton btnTools;
            private final MaterialButton btnRemove;

            ViewHolder(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tv_name);
                tvStatus = itemView.findViewById(R.id.tv_status);
                tvTools = itemView.findViewById(R.id.tv_tools);
                ivStatus = itemView.findViewById(R.id.iv_status);
                btnTools = itemView.findViewById(R.id.btn_tools);
                btnRemove = itemView.findViewById(R.id.btn_remove);
            }

            void bind(MCPServer server) {
                tvName.setText(server.getServerName());

                boolean connected = server.isConnected();
                tvStatus.setText(connected ? "已连接" : "未连接");
                tvStatus.setTextColor(getColor(connected ? R.color.status_success : R.color.status_error));
                ivStatus.setImageResource(connected ? R.drawable.ic_ai_success : R.drawable.ic_ai_error);

                int toolCount = server.getAvailableTools().size();
                tvTools.setText(String.format("%d 个工具", toolCount));

                btnTools.setOnClickListener(v -> showServerTools(server));

                btnRemove.setOnClickListener(v -> {
                    new AlertDialog.Builder(MCPManagerActivity.this)
                        .setTitle("确认删除")
                        .setMessage("确定要删除服务器 \"" + server.getServerName() + "\" 吗？")
                        .setPositiveButton("删除", (dialog, which) -> {
                            mcpManager.removeServer(server.getServerName());
                            loadServers();
                        })
                        .setNegativeButton("取消", null)
                        .show();
                });
            }
        }
    }
}
