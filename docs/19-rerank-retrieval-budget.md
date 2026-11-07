# 重排与检索预算：本轮实现和学习入口

本轮是在现有五模块工程里实现“单路向量召回后重排、分层预算、前后对比”。保留认证、解析与入库状态、用户自定义提示词、问题改写和原子问答保存，不新增Maven模块、依赖或数据库迁移。本轮前备份在被忽略的`data/rerank-baseline-20261106-1793977543414`；Git不自动提交或推送。

## 原源码对应与取舍

核对原项目提交[200dca86ac04d3a27f7cfffd7d3dd4d2b1c41129](https://github.com/nageoffer/ragent/commit/200dca86ac04d3a27f7cfffd7d3dd4d2b1c41129)，不是只参考本机旧副本。

| 原项目源码 | 学习版位置和取舍 |
| --- | --- |
| [RetrievalBudget](https://github.com/nageoffer/ragent/blob/200dca86ac04d3a27f7cfffd7d3dd4d2b1c41129/rag/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/RetrievalBudget.java) | `rag.core.retrieval.RetrievalBudget`保留三段预算；当前只有单通道，采用最终≤候选≤召回的约束 |
| [RerankPostProcessor](https://github.com/nageoffer/ragent/blob/200dca86ac04d3a27f7cfffd7d3dd4d2b1c41129/rag/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/postprocessor/RerankPostProcessor.java) | 同名组件放`rag.core.retrieval.postprocessor`，接收已验证候选，调用模型并产生排序和状态，不把业务塞进Controller |
| [BaiLianRerankClient](https://github.com/nageoffer/ragent/blob/200dca86ac04d3a27f7cfffd7d3dd4d2b1c41129/infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/rerank/BaiLianRerankClient.java) | `infra-ai`内同名客户端实现RerankService，保留原生协议与index映射；使用已有JDK HTTP/Jackson，不引入上游整个路由框架 |

与上游的差异：本轮请求整个候选池的分数，随后再按条数和字符选最终来源，避免只拿Top-K后因大块超预算而无后续可选。上游允许部分响应补位；学习版要求索引完整且唯一，无效响应整批回退向量排序。旧`score`继续代表向量余弦分，另加可空`rerankScore`，便于兼容旧历史。未实现多路融合、通道归因、证据闸门、路由故障转移或Agent。

## 实际输入与输出

```text
ChatController：可信userId、会话ID、原问题、知识库ID、可选rerank
  → ChatServiceImpl：验证归属/模式，在同会话锁中读取历史
  → DefaultRagWorkflow.prepare：QueryRewriteService产出searchQuestion
  → KnowledgeRetrievalServiceImpl.retrieve
      EmbeddingClient.embed(searchQuestion) → 问题向量
      KnowledgeVectorMapper.search(..., recallBudget=16) → 至多16个可信来源
      截候选池(candidateLimit=12) → 至多12个来源
      RerankPostProcessor.process(searchQuestion, candidates, ...)
        → RerankService / BaiLianRerankClient
        → List<Score(index,relevance)> + 可选usage
        → 按index从原候选复制，仅追加rerankScore，按分数降序
      最终选择(contextTopK=4，maxContextCharacters=6000) → 至多4个完整来源
  → RagContext：原问题改写结果、最终来源、检索观察、可选直接拒答
  → DefaultRAGPromptService：按最终顺序赋citationId，组织当前资料与问题
  → LLMService：生成回答
  → ChatMemoryRepository.appendTurn：短事务保存原问题、完整答案、最终来源快照
  → 普通JSON或SSE done：answer/sources/knowledgeBaseId/retrievalQuery/retrieval
```

重排不生成答案，也不会修改用户的问题或文档。它用同一个问题逐个比较候选正文，返回候选索引与相关分。候选再少也可能需要排序和出分，因此不能因数量≤最终Top-K就直接跳过。没有进入候选池的事实，重排无法找回来。

| 字段/配置 | 意义 |
| --- | --- |
| recallBudget / RAG_RECALL_BUDGET | SQL召回上限，默认16 |
| candidateLimit / RAG_RERANK_CANDIDATE_LIMIT | 送入重排的候选池上限，默认12 |
| topK(contextTopK) / RAG_TOP_K | 最终资料条数上限，默认4；上限20 |
| minScore / RAG_MIN_SCORE | 召回时的向量余弦阈值，默认0.35；不用于过滤重排分 |
| RAG_MAX_CONTEXT_CHARS | 最终来源正文合计默认6000 Unicode码点；超出时跳过整块继续选后续，不改动来源位置 |
| RAG_RERANK_MAX_INPUT_CHARS | 默认24000，按Σ(问题字符数+候选正文字符数)粗略控制重排输入；超限显式跳过 |

三段数量须满足`1 ≤ contextTopK ≤ candidateLimit ≤ recallBudget ≤ 100`。每次预览可以覆盖；聊天默认用服务配置，可用布尔`rerank`单独覆盖。只覆盖topK且超过默认预算时，缺省的上游数量会相应提高；显式矛盾配置返回400。字符数不是Token数，也不保证符合提供方单条Token限制，长问题/长片段仍可能被平台拒绝。

## 本地配置与页面验收

保留现有DB、Redis、聊天和Embedding环境变量。在IDEA同一运行配置添加：

```text
RAG_RERANK_ENABLED=true
RERANK_MODEL=qwen3-rerank
RERANK_URL=从百炼控制台/官方文档复制的原生text-rerank完整地址
```

密钥默认复用`LLM_API_KEY`；不同地域或业务空间使用独立密钥时设置`RERANK_API_KEY`。默认完整响应总超时3000ms，可通过`RERANK_TIMEOUT_MS`配置100～30000ms。配置示例在根`.env.example`，Spring Boot不会自动读取该文件。配置后重启bootstrap，无需重新解析/向量化已有资料。

模型固定默认`qwen3-rerank`。原生请求为`model + input.query/documents + parameters.top_n/return_documents`，按官方文本排序接口实现；完整URL按实际空间、地域填写。此客户端不接受需要另一种载荷的OpenAI兼容`/compatible-api/v1/reranks`协议，不能把聊天地址直接替换成排序地址后缀。[百炼官方接口与地域/空间示例](https://help.aliyun.com/zh/model-studio/text-rerank-api)。

默认`RAG_RERANK_ENABLED=false`以保留现有调用行为。关闭时普通/知识库回答仍可使用向量排序；知识库页点击“对比重排前后”会明确执行一次重排，与复选框无关。端点缺失显示`FALLBACK_NOT_CONFIGURED`，不能把有答案视为重排成功。

1. 打开知识库页，选择已有有效索引的知识库，在“检索资料”区域填具体问题。
2. 保持16/12/4，点击“对比重排前后”，观察两侧候选顺序、两个分数、最后采用的来源和状态。
3. 重点展开原本较靠后的证据，核对是否真的含所问事实；高分不等于已证实事实。
4. 回聊天页，用新会话提同一问题；核对“重排已应用”、最终引用与答案。正常/SSE共用一条检索链路。
5. 比较8/8/4、16/12/4等设置；召回池过小导致的漏证据不能归因于重排。

普通聊天不会调用重排。切换知识库仍需新会话；知识库里没有手机号等事实时，重排不能让模型编造。基础提示词、RAG事实约束和旧助手正文隔离继续保留。

## 观察、失败和一致性

旧`POST /search`仍返回来源数组。新`/search/detail`返回`sources + retrieval`；新`/search/compare`返回同一快照上的`baseline + reranked`。观察字段含三段预算/数量、候选前后顺序、各阶段毫秒数、资料/输入字符数、模型标识、状态、错误码、尝试数及上游可选Token用量。列表不重复返回全部候选正文。

向量分与重排分是不同标尺，不能共用阈值，也不能把重排分当作正确概率。比较接口的两个totalMs共享召回时间，不能相加当作两次请求耗时；比较请求另记录实际总耗时。`rerankAttempts`是适配器尝试次数，不是计费凭证；usage缺失记null，不猜测Token或金额。

| 状态 | 行为 |
| --- | --- |
| APPLIED | 模型返回完整合法索引，按重排分选择来源 |
| DISABLED | 明确关闭，保留向量排序 |
| SKIPPED_EMPTY / SKIPPED_BUDGET | 无候选或输入超过字符预算，不发重排请求 |
| FALLBACK_NOT_CONFIGURED | 尚未配置端点/密钥；保留向量排序并说明 |
| FALLBACK_TIMEOUT / FALLBACK_ERROR | 远程超时、鉴权/限流/网络/协议故障，保留原向量排序，不自动重试 |
| 取消 | 传播取消异常并停止整轮；不转成回退后继续回答 |

客户端只信任index和有限的0～1分数，拒绝重复、越界、遗漏、非整数索引与无效分数；忽略上游回传正文，避免篡改本地来源。完整响应读取阶段限制256KiB，并以同一Future轮询检查总截止时间与取消。模型等待不进入数据库事务。SSE等待重排期间停止/超时同样受原取消与提交协调保护；中断轮次没有完整消息和done。

最终来源顺序用于本轮citationId和sources_json保存，附加可空rerankScore。旧JSON无此字段可以继续读取；不会批量回写历史分数。检索观察只随本次响应返回，未实现持久化全链路Trace或Langfuse。

## 对比工具和验证边界

`scripts/compare-retrieval.mjs`在同一候选集对比文件命中、MRR和可选`expectedEvidence`字面覆盖。题集、配置、Embedding模型/profile、重排模型/状态、阶段耗时、实际返回用量均写报告。题集示例在`docs/examples/rerank-eval.json`，只对已上传并入库的同名合成资料有效；没有标注的题目不进入相应分母。

```powershell
# IDEA或当前终端预先设置RAG_EVAL_TOKEN、RAG_EVAL_KNOWLEDGE_BASE_ID；不要把Token写进脚本。
node scripts/compare-retrieval.mjs docs/examples/rerank-eval.json target/rerank-local-report.json --allow-model-calls
```

可追加`--with-answers`：每题再建两个新会话，分别关闭/开启重排，实际生成并保存答案供人工判断。它会额外调用Embedding、重排/回答模型，并写入本账号聊天记录；应使用独立评测账号/知识库。答案请求使用聊天默认预算；预览覆盖其他预算或资料变化造成证据不一致时，报告`NOT_COMPARABLE`，不可宣称答案前后效果差异。

`scripts/evaluate-retrieval.mjs`保留原调用方式，增加检索观察与独立重排分，适合单方案文档级检索基线。这些工具都不是RAGAS：文件命中不保证片段证据完整，字面覆盖不保证模型忠实，答案是否捏造流程/金额仍需人工或额外质量评测。

自动验证包含原完整回归与新协议/预算/取消/来源测试，均使用独立容器、存储与本机假模型；确定性测试把第7名证据提到前面只证明代码能正确使用排序，不证明真实模型准确率提升。真实百炼端点、密钥权限、付费调用效果及固定题集答案质量，需配置后单独验收。最终实际通过数与报告路径见README及验收JSON。

建议按`RetrievalBudget → KnowledgeRetrievalServiceImpl.recall/finish → RerankPostProcessor.process → BaiLianRerankClient.rerank/decode → ChatServiceImpl.response`阅读，再用知识库页对照每一步的输入输出。理解重点是候选在哪里被截断、两个分数如何保留、超时为何能回退而取消必须停止，以及来源快照为何仍按最终顺序保存。
