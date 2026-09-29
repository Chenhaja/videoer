# 秒级视频证据定位技术设计

## 1. Design Decision

采用“窗口上下文不变，精确证据旁路索引”的兼容方案。

```text
60 秒音频切片                 仍然保留，控制 ASR 请求规模
60 秒 VideoSegment            仍然进入 Agent，保持当前上下文形状
5 分钟 VideoChunk              仍然负责摘要、Embedding 和候选召回
精确证据索引                   只在服务端用于 anchorText -> 时间解析
AnalysisResult.Evidence        Agent 只增加 anchorText，服务端补 timestampPrecision
```

这个边界避免把完整原子证据列表放大 Agent prompt，同时避免让 60 秒窗口继续承担最终定位责任。

## 2. Data Contracts

### 2.1 Agent Input

现有 `VideoContext` 主体保持兼容：`VideoSegment` 仍按 60 秒窗口提供 `startMs`、`endMs`、聚合后的 transcript、OCR 文本和 evidence frame。精确索引不直接序列化到 Agent prompt。

### 2.2 Agent Raw Output

扩展 `AnalysisResult.Evidence`：

```json
{
  "timestampMs": 180000,
  "source": "ASR",
  "content": "第三分钟的完整原始语音证据",
  "anchorText": "模型通过反向传播更新参数",
  "claim": "视频介绍了反向传播训练过程"
}
```

Rules:

- `timestampMs` 必须是 Agent 上下文中某个 `VideoSegment.startMs`，仅作为窗口锚点。
- `anchorText` 必须从 `content`/上下文中原样复制，是支撑该 Claim 的第一条句子或 OCR 文本；没有可靠锚点时输出空字符串。
- `content` 继续承载当前完整支撑文本，不替换为索引 ID，也不要求 Agent 输出所有原子证据。
- `claim` 必须原样复制对应 conclusion。
- `source` 兼容现有 `ASR`、`OCR`、`ASR+OCR` 值；精确化时只对可单独归属的 `ASR` 或 `OCR` 过滤候选。`ASR+OCR` 保留当前混合来源语义和窗口级时间，不参与本次精确化。
- Agent prompt 明确禁止自行填写秒级 `timestampMs` 或 `timestampPrecision`。

Confirmed boundary: ASR sentence timestamps are optional. If the configured ASR provider returns no usable sentence spans, the normalized ASR branch still produces the existing minute-level transcript and produces no ASR precision records. OCR precision remains independently available.

### 2.3 Enriched Output

服务端在 Executor 结果生成后、Critic 校验前，写入：

```json
{
  "timestampMs": 186400,
  "timestampPrecision": "SECOND",
  "source": "ASR",
  "content": "第三分钟的完整原始语音证据",
  "anchorText": "模型通过反向传播更新参数",
  "claim": "视频介绍了反向传播训练过程"
}
```

`timestampPrecision` 取值：

- `SECOND`: 命中了真实 ASR 句子或 OCR 关键帧时间；毫秒值用于播放器跳转，UI 可按秒展示。
- `MINUTE`: 没有可靠精确匹配，`timestampMs` 是 60 秒窗口起点。

缺失该字段的旧 Checkpoint 按 `MINUTE` 兼容读取。该字段由服务端生成，不能由模型直接控制。

## 3. Precision Index

新增内部不可直接暴露给 Agent 的媒体级索引，建议以独立 Checkpoint 保存，名称类似 `precisionEvidence`：

```java
record PrecisionEvidence(
        long startMs,
        long endMs,
        String source,
        String text,
        String frameUrl
) {}
```

索引记录要求：

- ASR 记录来自 ASR provider 返回的句级 `start/end`，加上音频切片偏移 `chunkIndex * 60_000` 后转成原视频全局时间。
- OCR 记录来自现有 `FramePart.timestampMs` 和 OCR 文本，保留实际关键帧时间及已上传帧地址。
- provider 未返回 ASR 句级时间时，不把 60 秒窗口或句子序号伪装成精确 ASR 时间；只保存分钟级上下文。
- 索引按媒体复用，不按 Agent goal 复制；删除媒体时随 `AgentCheckpointService.deleteMedia` 清理。
- 内容级上下文复用时，必须同时复用精确索引；没有索引的旧上下文允许继续工作但只能分钟回退。
- The initial persistence choice is the existing media checkpoint payload with Redis caching; payload-size telemetry/limits must be measured before considering a dedicated table.

## 4. Resolution Algorithm

`EvidencePrecisionService`（名称可按实现调整）负责将 Agent Evidence 解析成最终 Evidence：

1. 以 Agent `timestampMs` 找到所属 60 秒 `VideoSegment`；不能找到时直接返回 `MINUTE` 并触发 Critic 的无效证据处理。
2. 读取该媒体的精确证据索引，并按窗口范围过滤。
3. 按 `source` 过滤候选：ASR 只查 ASR；OCR 只查 OCR；ASR+OCR 两路都查。
4. 对 `anchorText` 与候选 `text` 做与现有 `EvidenceVerificationService` 一致的 Unicode 小写、标点/空白归一化。
5. 选择规范化文本完全相等或候选文本包含 anchorText 的最早 `startMs` 记录；同一时间按 source 稳定排序。
6. 命中 ASR 时使用 `[startMs,endMs)`；命中 OCR 时使用 `[timestampMs,timestampMs+1)` 或现有兼容范围，并把 `timestampMs` 设置为帧时间。
7. 未命中、anchorText 为空、索引缺失或来源不匹配时，保留原窗口起点并设为 `MINUTE`，不得选择该窗口“第一条句子”冒充 anchor 命中。

精确化必须是幂等的：已是 `SECOND` 的结果重复处理不能改变时间；已是 `MINUTE` 的结果只有在提供相同精确索引和有效 anchorText 时才升级。

## 5. AgentLoop and Verification Flow

```text
VideoContext build
  ├─ 60 秒切片 + ASR
  ├─ 关键帧 + OCR
  ├─ 保存 VideoContext
  └─ 保存 precisionEvidence sidecar
        ↓
LongVideoContextService
  ├─ 5 分钟 Chunk 召回
  └─ 返回原有 60 秒窗口上下文
        ↓
Executor
  └─ 输出窗口 timestampMs + anchorText
        ↓
EvidencePrecisionService
  └─ 解析成 SECOND 或 MINUTE
        ↓
Critic + EvidenceVerificationService
  ├─ 校验 claim/content/source
  └─ SECOND 证据必须命中精确索引；MINUTE 证据保留兼容警告
        ↓
Checkpoint + Markdown/API
```

`EvidenceVerificationService` 不应只用 `VideoSegment` 的分钟范围判断精确证据。建议新增精确索引参数或专门的 `supportsPreciseEvidence` 方法：

- `MINUTE` 结果继续使用现有窗口级兼容校验；
- `SECOND` 结果必须校验 `anchorText`、source 和精确索引时间一致；
- `content` 可以是窗口级聚合文本，但不能成为精确定位的唯一依据。

### Existing ASR+OCR behavior and confirmed compatibility boundary

Today `AnalysisResult.Evidence` has one `timestampMs` and permits `source=ASR+OCR`. Verification concatenates the transcript and all OCR text in the same 60-second `VideoSegment`, and retrieval/UI expose that single window interval. This behavior remains unchanged for the output shape: no `timestamps[]` and no split Evidence entries.

For a new `ASR+OCR` result, the precision resolver leaves the existing single window-level timestamp behavior unchanged. It does not search across ASR and OCR precision records, does not split the Evidence, and does not upgrade the mixed-source result to `SECOND`. This avoids changing the established meaning of a mixed-source Evidence; only `ASR` or `OCR` results with an independently attributable anchor can receive second-level precision.

`AgentEvaluationService` 增加/调整指标时保留现有指标含义，并新增可选的 `secondPrecisionRate`，分母只统计有 anchorText 或可精确索引能力的 Evidence，避免历史分钟数据污染新指标。

## 6. Persistence and Compatibility

优先复用 `AgentCheckpointRepository` 的 JSON payload + MySQL 真源/Redis 热缓存模式，不新增专用业务表，除非实际索引规模证明单个 checkpoint payload 不可接受。建议为 `AgentCheckpointService` 增加：

- `savePrecisionEvidence(mediaId, index)`；
- `loadPrecisionEvidence(mediaId)`；
- 删除媒体时由现有 `deleteMedia` 一并删除。

如果 `AgentCheckpointService.saveContext` 与精确索引需要原子落盘，使用一个内部 context build result 在 `AiService.buildContext` 中同时保存两者；任何一个保存失败都不能登记内容级 context owner。旧 context 仍可读取，精度服务返回空索引并回退分钟。

## 7. Frontend Compatibility

前端结果 Markdown 仍由 `AnalysisResult.toMarkdown()` 生成 `[mm:ss]` 链接，使用最终精确化后的 `timestampMs`。如果要显示精度，只增加非破坏性的 `timestampPrecision` 展示，不改变 `#video-t=<seconds>` 链接格式。

证据搜索接口 `VideoEvidenceHit` 可以继续返回 `startMs/endMs/source/snippet/transcript/ocrTexts`；后续若要显示精确度，再增加可选 `timestampPrecision`，旧客户端忽略未知字段即可。

## 8. Failure and Rollback

- ASR provider 不支持 verbose/segment timestamps：ASR 仍生成当前分钟上下文，ASR 精确索引为空，结果为 `MINUTE`；OCR 不受影响。
- OCR 识别失败或帧上传失败：保留已有分支降级行为；若无 OCR 文本则不生成 OCR 精确证据。
- 精确化解析异常：记录稳定 warn 事件并返回未精确化的原结果，不阻断 Agent 主流程，除非证据本身无法通过现有校验。
- Redis 热缓存异常：从 MySQL checkpoint 读取索引；MySQL 失败则按无索引回退并遵循现有 checkpoint 错误策略。
- 结果结构反序列化失败：不能静默吞掉；沿用现有 checkpoint 错误处理和受控 API 错误。

## 9. Non-goals

- 不改变 60 秒 ASR 切片和 5 分钟 Chunk 召回边界。
- 不要求 Agent 看见或输出完整原子证据列表。
- 不用 LLM 推断未被 ASR/OCR 真实返回的时间。
- 不为历史没有精确索引的数据制造秒级时间。
