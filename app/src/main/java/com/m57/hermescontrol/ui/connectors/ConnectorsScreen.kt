package com.m57.hermescontrol.ui.connectors

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.mcp.McpServersActions
import com.m57.hermescontrol.ui.mcp.McpServersBody
import com.m57.hermescontrol.ui.mcp.McpServersViewModel
import com.m57.hermescontrol.ui.mcp.McpTab

private enum class ConnectorsTab { HOSTED, MCP }

/** One drawer screen for hosted app connectors and local MCP servers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectorsScreen(
    onOpenDrawer: (() -> Unit)? = null,
    accountsViewModel: AccountConnectorsViewModel = viewModel { AccountConnectorsViewModel() },
    mcpViewModel: McpServersViewModel = viewModel { McpServersViewModel() },
) {
    var tab by rememberSaveable { mutableStateOf(ConnectorsTab.HOSTED) }
    val accounts by accountsViewModel.state.collectAsStateWithLifecycle()
    val mcp by mcpViewModel.uiState.collectAsStateWithLifecycle()

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_account_connectors)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing =
            when {
                tab == ConnectorsTab.HOSTED -> accounts.loading
                mcp.selectedTab == McpTab.INSTALLED -> mcp.isLoading
                else -> mcp.catalogLoading
            },
        onRefresh = {
            when {
                tab == ConnectorsTab.HOSTED -> accountsViewModel.refresh()
                mcp.selectedTab == McpTab.INSTALLED -> mcpViewModel.loadServers(forceRefresh = true)
                else -> mcpViewModel.loadCatalog()
            }
        },
        actions = { if (tab == ConnectorsTab.MCP) McpServersActions(mcpViewModel) },
    ) {
        Column(Modifier.fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SegmentedButton(
                    selected = tab == ConnectorsTab.HOSTED,
                    onClick = { tab = ConnectorsTab.HOSTED },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.connectors_tab_hosted)) }
                SegmentedButton(
                    selected = tab == ConnectorsTab.MCP,
                    onClick = { tab = ConnectorsTab.MCP },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(stringResource(R.string.connectors_tab_mcp)) }
            }
            when (tab) {
                ConnectorsTab.HOSTED -> {
                    AccountConnectorsBody(accountsViewModel, Modifier.fillMaxSize())
                }

                ConnectorsTab.MCP -> {
                    McpServersBody(mcpViewModel, Modifier.fillMaxSize())
                }
            }
        }
    }
}
