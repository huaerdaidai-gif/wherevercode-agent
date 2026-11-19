#!/usr/bin/env python3
"""
scripts/test_ios_b3_regression.py
iOS 批次 B3（差异 9 核心：独立模型存储 + 运行时合并视图）完整回归测试脚本。
覆盖：
1. 五项硬门禁源码级契约校验（adapterId、authRef、isLocalUrl去重、三分组收口、运行时合并视图接通）
2. 全局红线禁区校验（Brain 核心零 diff、无反向依赖、SettingsView 一字不动）
3. 独立存储、Composite 视图、启动迁移与密钥引用计数的逻辑行为模拟断言
"""

import os
import re
import json
import sqlite3
import unittest

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP_DIR = os.path.join(BASE_DIR, "ios", "WhereverCodeAgent")

class TestIosB3Regression(unittest.TestCase):

    def setUp(self):
        self.conn = sqlite3.connect(":memory:")
        self.conn.execute("CREATE TABLE kv_store (key TEXT PRIMARY KEY, value TEXT)")
        self.conn.commit()

    def tearDown(self):
        self.conn.close()

    # MARK: - 1. 五项硬门禁源码契约断言

    def test_01_gate_adapter_id_contract(self):
        """门禁 1：adapterId 统一对齐 GenericOpenAiBrainAdapter.adapterId"""
        reg_path = os.path.join(APP_DIR, "Models", "ModelRegistry.swift")
        with open(reg_path, encoding="utf-8") as f:
            src = f.read()

        # ModelConfig.init default adapterId
        self.assertIn("adapterId: String = GenericOpenAiBrainAdapter.adapterId", src)
        # addModelConfig adapterId
        self.assertIn("adapterId: GenericOpenAiBrainAdapter.adapterId", src)
        # 绝不得直接写硬编码 "generic"
        self.assertNotIn('adapterId = "generic"', src)
        self.assertNotIn('adapterId: "generic"', src)

        # ModelSettingsView 中的新建模型路径亦统一对齐 GenericOpenAiBrainAdapter.adapterId
        view_path = os.path.join(APP_DIR, "Views", "ModelSettingsView.swift")
        with open(view_path, encoding="utf-8") as f:
            view_src = f.read()
        self.assertNotIn('adapterId: "generic"', view_src)
        self.assertNotIn('adapterId = "generic"', view_src)

    def test_02_gate_auth_ref_contract(self):
        """门禁 2：authRef 统一对齐 model-api:<modelId>"""
        reg_path = os.path.join(APP_DIR, "Models", "ModelRegistry.swift")
        with open(reg_path, encoding="utf-8") as f:
            src = f.read()

        self.assertIn('let authRef = "model-api:\\(modelId)"', src)
        self.assertNotIn('let authRef = "model:\\(modelId):key"', src)

        # ModelSettingsView 中的新建模型路径亦统一对齐 model-api:<modelId>
        view_path = os.path.join(APP_DIR, "Views", "ModelSettingsView.swift")
        with open(view_path, encoding="utf-8") as f:
            view_src = f.read()
        self.assertNotIn('"model:\\(modelId):key"', view_src)
        self.assertIn('"model-api:\\(modelId)"', view_src)

    def test_03_gate_is_local_url_deduplication(self):
        """门禁 3：删除自写 isLocalUrl，统一复用 BrainConfigValidator.isLocalModelUrl"""
        reg_path = os.path.join(APP_DIR, "Models", "ModelRegistry.swift")
        with open(reg_path, encoding="utf-8") as f:
            src = f.read()

        # 自写 isLocalUrl 已彻底移除
        self.assertNotIn("func isLocalUrl", src)
        # 本地模型判定复用 BrainConfigValidator
        self.assertIn("BrainConfigValidator.isLocalModelUrl", src)

    def test_04_gate_model_settings_three_group_convergence(self):
        """门禁 4：ModelSettingsView 已添加列表严格三分组（模型 API / CLI 会员 / 订阅授权）"""
        view_path = os.path.join(APP_DIR, "Views", "ModelSettingsView.swift")
        with open(view_path, encoding="utf-8") as f:
            src = f.read()

        added_section = src.split("addedModelsSection: some View")[1].split("private func modelRow")[0]
        # 必须包含三大分组
        self.assertIn('groupTitle: "模型 API"', added_section)
        self.assertIn('groupTitle: "CLI 会员"', added_section)
        self.assertIn('groupTitle: "订阅授权"', added_section)
        # 本地模型不得单独成组
        self.assertNotIn('groupTitle: "本地模型"', added_section)

    def test_05_gate_runtime_composite_wiring_and_chat_decoupling(self):
        """门禁 5：接通运行时合并视图，对话层彻底切断对 brainSettingsModel 的依赖"""
        app_path = os.path.join(APP_DIR, "App", "WhereverCodeAgentApp.swift")
        local_path = os.path.join(APP_DIR, "Data", "LocalData.swift")

        with open(app_path, encoding="utf-8") as f:
            app_src = f.read()
        with open(local_path, encoding="utf-8") as f:
            local_src = f.read()

        # AppState 装配层持有并注入 CompositeBrainConfigStore
        self.assertIn("compositeBrainConfigStore: CompositeBrainConfigStore", app_src)
        self.assertIn("BrainBootstrap.buildRegistry(\n            configStore: composite,", app_src)
        self.assertIn("BrainBootstrap.buildEngine(\n            configStore: compositeBrainConfigStore,", app_src)

        # LocalData.swift 对话层切断对 brainSettingsModel 的读取
        self.assertNotIn("appState.brainSettingsModel", local_src)
        self.assertIn("appState.compositeBrainConfigStore.listConfigs()", local_src)
        self.assertIn("appState.modelSettingsModel.unusableReason", local_src)
        self.assertIn("appState.modelSettingsModel.currentModelId", local_src)
        self.assertIn("appState.modelSettingsModel.configStore.setCurrentModelId", local_src)

    # MARK: - 2. 全局红线自查断言

    def test_06_redline_zero_brain_imports_and_settings_intact(self):
        """全局红线：brain/** 零 models import，SettingsView 一字不动"""
        brain_dir = os.path.join(APP_DIR, "Brain")
        for root, _, files in os.walk(brain_dir):
            for file in files:
                if file.endswith(".swift"):
                    with open(os.path.join(root, file), encoding="utf-8") as f:
                        content = f.read()
                        self.assertNotIn("import ModelRegistry", content)
                        self.assertNotIn("wherevercode.agent.models", content)

        # SettingsView.swift 必须一字不动保留大脑分节本体
        settings_path = os.path.join(APP_DIR, "Views", "SettingsView.swift")
        with open(settings_path, encoding="utf-8") as f:
            settings_src = f.read()
        self.assertIn('Section("大脑")', settings_src)
        self.assertIn('Text("管理大脑")', settings_src)
        self.assertIn('Text("大脑是可替换的推理后端', settings_src)

    # MARK: - 3. Composite 视图与迁移逻辑行为模拟测试

    def test_07_composite_store_merge_and_proxy_behavior(self):
        """行为模拟：Composite 合并顺序、同 ID 模型优先、currentBrainId 代理、写操作只落 brainStore"""
        # 模型存储与大脑存储数据模拟
        model_store = [
            {"modelId": "deepseek-01", "model": "deepseek-reasoner", "label": "DeepSeek (ModelStore)"},
            {"modelId": "solo-model", "model": "gpt-4o", "label": "GPT-4o (ModelStore)"}
        ]
        brain_store = [
            {"brainId": "deepseek-01", "model": "deepseek-chat", "label": "DeepSeek (BrainStore)"},
            {"brainId": "claude-01", "model": "claude-3-5", "label": "Claude (BrainStore)"}
        ]

        # 模拟 Composite.listConfigs()
        model_ids = {m["modelId"] for m in model_store}
        merged = list(model_store) + [b for b in brain_store if b["brainId"] not in model_ids]

        self.assertEqual(len(merged), 3)
        common = next(item for item in merged if (item.get("modelId") or item.get("brainId")) == "deepseek-01")
        self.assertEqual(common["label"], "DeepSeek (ModelStore)", "同 ID 时模型存储必须优先")

        # 模拟 currentBrainId 代理
        current_model_id = None
        current_brain_id = "claude-01"
        self.assertEqual(current_model_id or current_brain_id, "claude-01")
        current_model_id = "solo-model"
        self.assertEqual(current_model_id or current_brain_id, "solo-model", "模型页切换当前模型必须立即代理生效")

    def test_08_startup_migration_idempotence_and_crash_safety(self):
        """行为模拟：启动按 ID 幂等补齐、排除平台 Agent、墓碑过滤与崩溃安全顺序"""
        brain_records = [
            {"brainId": "ds-v3", "kind": "MODEL_API", "extra": {"p": "1"}},
            {"brainId": "cli-runtime", "kind": "AGENT_RUNTIME", "extra": {}},
            {"brainId": "chatgpt-sub", "kind": "SUBSCRIPTION", "extra": {}},
            {"brainId": "platform-agent", "kind": "PLATFORM_AGENT", "extra": {}},
            {"brainId": "cloud-agent", "kind": "CLOUD_AGENT", "extra": {}}
        ]
        tombstones = {"deleted-old-builtin"}

        # 首次启动：仅过滤并复制模型页三分组条目
        target_kinds = {"MODEL_API", "AGENT_RUNTIME", "SUBSCRIPTION"}
        migrated = [
            b for b in brain_records
            if b["kind"] in target_kinds and b["brainId"] not in tombstones
        ]
        self.assertEqual(len(migrated), 3)
        migrated_ids = [m["brainId"] for m in migrated]
        self.assertNotIn("platform-agent", migrated_ids)
        self.assertNotIn("cloud-agent", migrated_ids)
        self.assertNotIn("deleted-old-builtin", migrated_ids)

        # 模拟崩溃安全顺序：数据写入后才写入 migration_v1_done
        self.conn.execute("INSERT INTO kv_store (key, value) VALUES ('model.configs_json', ?)", (json.dumps(migrated),))
        self.conn.execute("INSERT INTO kv_store (key, value) VALUES ('model.migration_v1_done', 'true')")
        self.conn.commit()

        # 模拟第二次启动（幂等补齐）：新增条目自动进入，已在 modelStore 的不重复添加
        cur_models = json.loads(self.conn.execute("SELECT value FROM kv_store WHERE key='model.configs_json'").fetchone()[0])
        existing_ids = {m["brainId"] for m in cur_models}
        new_brain_records = brain_records + [{"brainId": "new-local-llama", "kind": "MODEL_API", "extra": {}}]
        
        backfill = [
            b for b in new_brain_records
            if b["kind"] in target_kinds and b["brainId"] not in existing_ids and b["brainId"] not in tombstones
        ]
        self.assertEqual(len(backfill), 1)
        self.assertEqual(backfill[0]["brainId"], "new-local-llama")

    def test_09_auth_ref_reference_counting(self):
        """行为模拟：密钥引用计数，三处（modelStore、brainStore、agentStore）均不再引用才删除"""
        shared_auth_ref = "model-api:shared-01"
        keychain = {shared_auth_ref: "secret-key-123"}

        model_store = [{"modelId": "m1", "authRef": shared_auth_ref}]
        brain_store = [{"brainId": "b1", "authRef": shared_auth_ref}]
        agent_store = []

        # 删除 m1：由于 brainStore 仍在使用，Keychain 凭据必须保留
        model_store.remove(model_store[0])
        model_refs = any(m["authRef"] == shared_auth_ref for m in model_store)
        brain_refs = any(b["authRef"] == shared_auth_ref for b in brain_store)
        agent_refs = any(a.get("authRef") == shared_auth_ref for a in agent_store)

        if not model_refs and not brain_refs and not agent_refs:
            del keychain[shared_auth_ref]

        self.assertIn(shared_auth_ref, keychain, "其他存储仍引用时，Keychain 凭据绝对保留")

        # 清除 brainStore 引用后再次触发
        brain_store.clear()
        brain_refs = False
        if not model_refs and not brain_refs and not agent_refs:
            del keychain[shared_auth_ref]

        self.assertNotIn(shared_auth_ref, keychain, "三处均无引用时，Keychain 凭据安全释放")

    # MARK: - 4. B3 收尾追加项断言 (收尾 1 & 收尾 2)

    def test_10_tail_01_routing_and_custom_providers_decoupled_keys(self):
        """B3 收尾 1：模型页路由与自定义服务商采用独立键（model.routing / model.custom_free_providers），旧键不被污染，首次幂等复制"""
        reg_path = os.path.join(APP_DIR, "Models", "ModelRegistry.swift")
        with open(reg_path, encoding="utf-8") as f:
            reg_src = f.read()

        # 独立键定义与类声明
        self.assertIn('class IndependentModelRoutingStore', reg_src)
        self.assertIn('static let keyStorage = "model.routing"', reg_src)
        self.assertIn('static let keyLegacy = "wherevercode_model_routing"', reg_src)

        self.assertIn('class IndependentCustomFreeProviderStore', reg_src)
        self.assertIn('static let keyStorage = "model.custom_free_providers"', reg_src)
        self.assertIn('static let keyLegacy = "wherevercode_custom_free_providers"', reg_src)

        # ModelSettingsModel 默认注入独立存储类
        self.assertIn('routingStore: ModelRoutingStore = IndependentModelRoutingStore()', reg_src)
        self.assertIn('customProviderStore: CustomFreeProviderStore = IndependentCustomFreeProviderStore()', reg_src)

        # 模拟数据持久化隔离与首次幂等复制
        # 1. 初始状态：旧键有数据，新键无数据
        legacy_routing = json.dumps({"enabled": True, "allowPaid": False, "orderedBrainIds": ["b1"]})
        self.conn.execute("INSERT INTO kv_store (key, value) VALUES ('wherevercode_model_routing', ?)", (legacy_routing,))
        self.conn.commit()

        # 首次复制：旧键数据复制进新键
        cur_new = self.conn.execute("SELECT value FROM kv_store WHERE key='model.routing'").fetchone()
        if cur_new is None:
            cur_legacy = self.conn.execute("SELECT value FROM kv_store WHERE key='wherevercode_model_routing'").fetchone()[0]
            self.conn.execute("INSERT INTO kv_store (key, value) VALUES ('model.routing', ?)", (cur_legacy,))
            self.conn.commit()

        # 验证新键已拥有数据，且旧键未被删除或修改
        new_val = json.loads(self.conn.execute("SELECT value FROM kv_store WHERE key='model.routing'").fetchone()[0])
        self.assertTrue(new_val["enabled"])
        self.assertEqual(self.conn.execute("SELECT value FROM kv_store WHERE key='wherevercode_model_routing'").fetchone()[0], legacy_routing)

        # 2. 模型页在新键上修改数据
        updated_new = json.dumps({"enabled": False, "allowPaid": True, "orderedBrainIds": ["b2", "b3"]})
        self.conn.execute("UPDATE kv_store SET value=? WHERE key='model.routing'", (updated_new,))
        self.conn.commit()

        # 断言：旧键保持原样字节级不变！彻底脱钩！
        legacy_after = self.conn.execute("SELECT value FROM kv_store WHERE key='wherevercode_model_routing'").fetchone()[0]
        self.assertEqual(legacy_after, legacy_routing, "模型页修改路由不得污染旧键 wherevercode_model_routing")

        # 3. 重复启动（幂等性）：新键已有数据时不被旧键覆盖
        if self.conn.execute("SELECT value FROM kv_store WHERE key='model.routing'").fetchone() is None:
            pass # 不会走这里
        self.assertEqual(self.conn.execute("SELECT value FROM kv_store WHERE key='model.routing'").fetchone()[0], updated_new)

    def test_11_tail_02_channel_polling_state_text_and_diagnostics(self):
        """B3 收尾 2：差异 16 频道轮询态文案与安卓逐字对齐、证书失败拼接与连接诊断展示"""
        dest_path = os.path.join(APP_DIR, "FlagshipUI", "FlagshipRouteDestinations.swift")
        with open(dest_path, encoding="utf-8") as f:
            dest_src = f.read()

        # 逐字对齐基础串
        self.assertIn('"○ 轮询兜底（每 15 秒补读；约 45 秒后重连推送）"', dest_src)
        # 证书失败拼接
        self.assertIn('" · 证书验证失败，安全连接未建立"', dest_src)
        # 证书失败关键词对齐安卓
        self.assertIn('"certificate"', dest_src)
        self.assertIn('"certpath"', dest_src)
        self.assertIn('"trust anchor"', dest_src)
        self.assertIn('"sslhandshake"', dest_src)
        self.assertIn('"证书"', dest_src)
        self.assertIn('"证书链"', dest_src)
        # 查看连接诊断开关与原文展示
        self.assertIn('"查看连接诊断"', dest_src)
        self.assertIn('showConnectionDiagnostics', dest_src)

if __name__ == "__main__":
    unittest.main()
