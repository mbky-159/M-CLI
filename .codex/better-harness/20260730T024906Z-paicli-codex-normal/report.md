# Better Harness Task-Loop Report

## At a Glance

- Loop Effectiveness: 54/100 (changes only after comparable later task outcomes)
- Asset Health / Repair Progress: 0/100 (0 verified, 0 partial, 5 pending)
- Demonstrated autonomy radius: not observed (not observed; not observed confidence)
- Strongest loop: Not enough evidence difference to name one.
- Largest observed leak: Use the priority moves; no single loop is uniquely weakest.
- Top expected gain: No priority benefit is available in this evidence boundary.

## What You Can Rely On Today

- No reliable user outcome has been demonstrated in this evidence boundary yet.

## What You Gain Next

- No priority Harness move is available in this evidence boundary.



### Why these moves matter

### 人工批准后仍不可越过策略拒绝的边界缺少组合验证
- Priority: Medium · Evidence: not observed in this boundary
- Reason: AGENTS.md 明确要求用户不能批准被策略层拒绝的请求，代码路径也会在 HITL 批准后继续进入 ToolRegistry；现有测试分别覆盖 HITL 决策、PathGuard 越界和 CommandGuard 危险命令，但没有一条测试把“批准危险调用 → PathGuard/CommandGuard 再拒绝 → 无副作用并写入 policy deny 审计”串起来。该缺口使安全层次顺序在重构后可能退化而不被聚焦回归发现。
- Expected Output:
  1. 用一条可重复的聚焦回归证明 HITL 批准不能绕过路径或命令策略，并保留可审计的拒绝结果。

### 当前跨模块变更没有一条可发现的 affected-check 命令
- Priority: Medium · Evidence: not observed in this boundary
- Reason: 当前变更同时影响 ReAct、Plan、Team、ConversationLedger、OpenAI-compatible 重试和长期记忆去重；仓库已新增或修改相应测试，但 AGENTS.md 的验证矩阵只提供通用领域入口，mvn clean package 又默认跳过测试。Agent 因此无法从任务入口直接得知哪一组最小检查覆盖最终变更，容易只运行 quick 或单个邻近测试而遗漏跨路径不变量。
- Expected Output:
  1. 让 Agent 能从跨模块改动直接路由到一条覆盖最终状态的最小 Maven 检查集合。

### 变更任务的交接没有与最终状态绑定的验收证据
- Priority: Medium · Evidence: not observed in this boundary
- Reason: 近 30 天的 Codex 会话中，多次有变更的任务以助手 handoff 结束；唯一可见检查是通过的 git diff --check，但其与最终内容或行为的相关性未闭合，也没有结构化完成信号或用户验收。该证据不代表任务失败，却说明当前工作流无法区分“宣布完成”与“结果已被相关检查或用户接受”。
- Expected Output:
  1. 让后续任务的交付记录可区分助手声明、相关验证和真实验收。

### 根级 AGENTS.md 过载，关键操作规则被实现细节稀释
- Priority: Medium · Evidence: not observed in this boundary
- Reason: 当前唯一项目级 Codex Rule 是 240 行的根 AGENTS.md，且仓库没有嵌套 AGENTS.md。文件同时承载架构清单、provider 协议细节、长篇 TUI 行为和模块级规则；真正影响所有任务的安全边界、同步规则和验证矩阵直到中后段才出现。PAI.md 只有 31 行且与核心命令基本一致，因此问题不是缺少导航，而是根指南没有充分利用已存在的 docs/agents-reference.md 做渐进披露。
- Expected Output:
  1. 让 Agent 先获得短而高信号的全局规则，再按任务加载实现细节，降低上下文腐化风险。

### 持久偏好需求没有后续独立任务验证其应用效果
- Priority: Low · Evidence: not observed in this boundary
- Reason: 会话事实中出现过明确的全局偏好保存请求，并观察到变更与 handoff；本次审查按授权不读取 Memory 正文，也没有后续独立任务证明该偏好被检索、与任务相关、实际应用且减少重复纠正。因此不能断言保存失败，但当前闭环缺少一个不暴露 Memory 内容的跨会话效果验证。
- Expected Output:
  1. 建立不暴露 Memory 正文的跨会话验证链，区分已保存、已应用和 later outcome improved。

## Five Lifecycle Dimensions

| Dimension | What the evidence proves | Evidence boundary | Summary | Boundary / blocker |
| --- | --- | --- | --- | --- |
| 任务理解 | Not observed yet | not observed in this boundary | AGENTS.md、PAI.md 与 README.md 的权威顺序和核心命令基本一致，但根级 Agent 指南过载，关键安全与验证规则被大量实现细节稀释。 | not observed |
| 可控执行 | Not observed yet | not observed in this boundary | HITL、PathGuard 与 CommandGuard 已在代码和分层单测中出现，但“批准仍不能越过策略拒绝”的组合边界尚未由一条聚焦验证证明。 | not observed |
| 改动验证 | Not observed yet | not observed in this boundary | 仓库拥有大量测试和 quick/full 路径，当前变更也配套增加了测试代码，但没有可发现的命令把 ledger、重试和记忆去重的最终变更映射到同一组相关检查。 | not observed |
| 可靠交付 | Not observed yet | not observed in this boundary | 会话中可以观察到交接，但没有与最终变更绑定的结构化完成信号、相关验证结果或真实验收边界。 | not observed |
| 经验沉淀 | Not observed yet | not observed in this boundary | 出现了明确的持久偏好需求，但本次范围不读取 Memory 正文，也没有后续独立任务证明检索、应用和结果改善，学习效果保持未观测。 | not observed |

## The 15 Small Checks

| Dimension | Small check | What the evidence proves | Evidence boundary |
| --- | --- | --- | --- |


## Evidence and Boundaries

- Episode coverage: 0 episodes, 0 edited, 0 closed, 0 repaired-and-passed
- Model: agent-work-loop-v4
- Session selection: not observed; 0 sessions analyzed of 0 eligible sessions; not observed confidence
- Delivery grades observed: not observed
- Source gaps: not observed
- Learning comparison: Not observed; 0 declared intervention(s)
