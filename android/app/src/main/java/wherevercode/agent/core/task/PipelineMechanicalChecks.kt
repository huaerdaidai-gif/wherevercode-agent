package wherevercode.agent.core.task

/**
 * P2 机械检查（2026-11-11）：先于 LLM 审查跑，全是代码、零 token。
 *
 * 今天真实可用的检查手段（逐项核过现有代码，如实写，不编）：
 * 1. 文件范围：改动文件清单（成员回传的 delivery.files，或本地
 *    write_file 步骤抽出的路径）对 plan.filesInScope。plan 未限定
 *    范围（空 = 整个项目家）时此项通过；无改动文件清单时 NOT_RUN。
 * 2. 测试 verdict：成员回传的 testVerdict（PASSED / FAILED /
 *    NOT_RUN）；本地路径今天没有任何测试 verdict 记录，调用方传
 *    NOT_RUN。plan 列了测试命令却未见测试结果 → FAILED；plan 没列
 *    测试命令 → NOT_RUN（不拦审查，如实记录）。
 * 3. 构建 / lint：本机没有可执行的构建或 lint 命令——App 代码里
 *    没有任何调用 gradle/lint 的执行器（构建只在 CI 跑
 *    `gradle :app:testDebugUnitTest` / `assembleDebug`）。此项恒为
 *    NOT_RUN，永不谎报通过、永不拦审查；等哪天 Hand 上有了可跑的
 *    构建命令再接进来。
 *
 * 门规则（[MechanicalCheckResult.passed]）：没有 FAILED 即通过；
 * FAILED 出现时审查不得通过（[PipelineOrchestrator.recordReview]
 * 拒绝 approve），直接打回 rework，不浪费 LLM 审查。
 */
object PipelineMechanicalChecks {

    /**
     * @param plan 已批准的 plan。
     * @param changedFiles 本次编码改动的文件清单（相对路径）；空 =
     *   无清单可核（不是“零改动”——零改动与无清单分开说，见 detail）。
     * @param testVerdict 本次编码的测试 verdict；无记录时 NOT_RUN。
     */
    fun run(
        plan: PlanArtifact,
        changedFiles: List<String>,
        testVerdict: PipelineTestVerdict,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): MechanicalCheckResult {
        val checks = mutableListOf<MechanicalCheck>()

        // 1. 文件范围。
        if (changedFiles.isEmpty()) {
            checks += MechanicalCheck(
                name = "文件范围",
                outcome = MechanicalOutcome.NOT_RUN,
                detail = "无改动文件清单可核（本地步骤里没有 write_file、也无成员回传的 files）",
            )
        } else if (plan.filesInScope.isEmpty()) {
            checks += MechanicalCheck(
                name = "文件范围",
                outcome = MechanicalOutcome.PASSED,
                detail = "plan 未限定文件范围（整个项目家），${changedFiles.size} 个改动文件无需核对范围",
            )
        } else {
            val outOfScope = changedFiles.filter { it !in plan.filesInScope }
            if (outOfScope.isEmpty()) {
                checks += MechanicalCheck(
                    name = "文件范围",
                    outcome = MechanicalOutcome.PASSED,
                    detail = "${changedFiles.size} 个改动文件全部在 plan 文件清单内",
                )
            } else {
                checks += MechanicalCheck(
                    name = "文件范围",
                    outcome = MechanicalOutcome.FAILED,
                    detail = "超出 plan 文件清单：${outOfScope.joinToString("、")}",
                )
            }
        }
        // plan 明确不碰的文件：改了就是 FAILED（与 filesInScope 是同一门的
        // 两面；两边都列时以此为准，detail 写清）。
        if (changedFiles.isNotEmpty() && plan.filesOutOfScope.isNotEmpty()) {
            val touched = changedFiles.filter { it in plan.filesOutOfScope }
            if (touched.isNotEmpty()) {
                checks += MechanicalCheck(
                    name = "禁改文件",
                    outcome = MechanicalOutcome.FAILED,
                    detail = "改动了 plan 明确不碰的文件：${touched.joinToString("、")}",
                )
            }
        }

        // 2. 测试 verdict。
        when (testVerdict) {
            PipelineTestVerdict.PASSED -> checks += MechanicalCheck(
                name = "测试",
                outcome = MechanicalOutcome.PASSED,
                detail = "测试 verdict：PASSED",
            )
            PipelineTestVerdict.FAILED -> checks += MechanicalCheck(
                name = "测试",
                outcome = MechanicalOutcome.FAILED,
                detail = "测试 verdict：FAILED",
            )
            PipelineTestVerdict.NOT_RUN -> {
                if (plan.testCommands.isNotEmpty()) {
                    checks += MechanicalCheck(
                        name = "测试",
                        outcome = MechanicalOutcome.FAILED,
                        detail = "plan 要求跑测试（${plan.testCommands.joinToString("；")}），但未见测试结果",
                    )
                } else {
                    checks += MechanicalCheck(
                        name = "测试",
                        outcome = MechanicalOutcome.NOT_RUN,
                        detail = "plan 未列测试命令、也未见测试结果（不拦审查，如实记录）",
                    )
                }
            }
        }

        // 3. 构建 / lint：今天没有可跑的命令（见文件头注释）。
        checks += MechanicalCheck(
            name = "构建/lint",
            outcome = MechanicalOutcome.NOT_RUN,
            detail = "本机无构建/lint 执行器（构建只在 CI 跑），未验证、不谎报通过",
        )

        return MechanicalCheckResult(checks, nowEpochSeconds)
    }
}
