# 支持秒级视频证据定位

## Goal

在不改变现有 Agent 上下文结构和 60 秒 ASR 处理窗口的前提下，把能够匹配到具体 ASR 句子或 OCR 关键帧的证据，从分钟级定位细化到秒级；无法可靠匹配时继续使用原有分钟级定位，不能伪造精确时间。

## Current Behavior

- `SegmentedTranscriptionService` 使用 FFmpeg 按 60 秒切分音频，并将一次 ASR 返回的整段文本包装成一个 `TranscriptSegment`。
- `VideoContextService` 将 ASR 文本和 OCR 关键帧合并到 60 秒 `VideoContext.VideoSegment`，当前合并会丢失 OCR 文本与关键帧时间的一一对应关系。
- `VideoChunkingService` 按 5 分钟生成摘要、关键词和 Embedding；5 分钟块用于长视频候选召回，不是最终证据单位。
- `VideoEvidenceRetrievalService` 和 `EvidenceVerificationService` 当前以 60 秒窗口作为命中与校验范围。
- `AnalysisResult.Evidence.timestampMs` 当前由 Agent 直接填写，前端据此跳转视频。

## Requirements

### Functional

1. 保留 60 秒音频切分、5 分钟语义块和当前 `VideoContext` 主体结构，避免为获得秒级定位而把所有原子证据发送给 Agent。
2. 在服务端为每个媒体保存独立的精确证据索引，至少支持：
   - ASR 句级证据：全局 `startMs`、`endMs`、原文；
   - OCR 关键帧证据：全局 `timestampMs`、OCR 原文、可选帧地址。
3. Agent 的证据输出增加 `anchorText`：它必须是当前上下文中直接支撑结论的第一条 ASR 句子或 OCR 文本，原样复制；Agent 不负责猜测句级毫秒时间。
4. Agent 输出的 `timestampMs` 继续表示 60 秒 `VideoSegment` 的起点，作为精确化查找范围和最终回退时间。
5. 服务端在 Executor 结果进入 Critic 前，根据 `timestampMs`、`source` 和 `anchorText` 查找精确证据：
   - 找到匹配的 ASR 句子或 OCR 帧时，替换为该证据的全局时间，并标记 `timestampPrecision=SECOND`；
   - `anchorText` 为空、无法匹配、索引缺失或 ASR/OCR 未提供精确时间时，保留分钟窗口起点，并标记 `timestampPrecision=MINUTE`。
   - `source=ASR+OCR` 继续按现有混合来源的窗口级语义处理，不参与本次秒级精确化，不拆分为 ASR/OCR 两条证据。
6. 现有 `content`、`claim`、`source` 字段继续保留；`claim` 必须原样复制被支持的 conclusion。
7. Evidence 校验和质量评估必须识别精确证据与分钟回退证据，不能仅因为时间戳落在 60 秒窗口内就把错误的 `anchorText` 当作精确证据。
8. 旧 Checkpoint 没有精确索引或旧结果没有 `anchorText` 时，必须能够正常读取并按分钟级行为兼容运行。
9. 前端继续使用最终 `timestampMs` 跳转；当结果携带 `timestampPrecision=MINUTE` 时可以展示精度状态，但不得破坏现有 Markdown 时间戳格式和证据搜索接口。

### Output Contract

Agent 的原始结构化输出为：

```json
{
  "timestampMs": 180000,
  "source": "ASR",
  "content": "第三分钟的完整原始语音证据",
  "anchorText": "模型通过反向传播更新参数",
  "claim": "视频介绍了反向传播训练过程"
}
```

服务端精确化后对外输出为：

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

回退结果为相同结构，但 `timestampMs=180000`、`timestampPrecision=MINUTE`。`timestampPrecision` 是服务端判定字段，Agent 不得自行填写或声称已经精确到秒。

## Constraints

- 不把所有 ASR 句子/OCR 帧逐条加入现有 Agent 上下文；现有 60 秒窗口文本仍用于理解和生成结果。
- 不把 5 分钟摘要直接当作 Evidence；摘要只负责候选窗口召回和语义背景。
- 不把文件字节上传分片 `client/src/chunkUpload.js` 与媒体分析的 60 秒音频切片混为一谈。
- 不对没有真实 ASR/OCR 时间戳的数据使用估算的秒数。
- 不改变现有 `Result<T>` API 信封、Checkpoint 按媒体/目标恢复机制、或视频播放器的 `timestampMs` 跳转单位。

## Acceptance Criteria

- [ ] 同一 60 秒切片内存在多条 ASR 句子时，Agent 只输出一条 `anchorText`，服务端能定位到匹配句子的全局秒级时间。
- [ ] OCR 查询命中关键帧文字时，服务端能定位到该帧的实际时间，而不是 60 秒窗口起点。
- [ ] ASR/OCR 精确索引缺失、`anchorText` 为空或文本不匹配时，结果回退到窗口起点并标记 `MINUTE`。
- [ ] 既有 `VideoContext`、5 分钟 Chunk 检索和 Agent 主要上下文内容保持兼容。
- [ ] Critic、EvidenceVerificationService 和 AgentEvaluationService 不会把错误的精确证据判定为已核验。
- [ ] 旧 Checkpoint、Demo 数据和前端旧结果仍可读取和展示。
- [ ] 新增后端单元测试覆盖精确匹配、OCR 匹配、回退、来源过滤、边界时间和旧数据反序列化；前端测试覆盖精度字段缺失/存在时的兼容处理。
- [ ] `server/./mvnw test` 和 `client/npm test` 通过；前端构建在依赖可用的环境中通过。

## Out Of Scope

- 将 FFmpeg 的 60 秒切片改为句子级或秒级切片。
- 重写 5 分钟摘要/向量检索为原子证据向量库。
- 要求 Agent 输出全部原子证据列表或新的多层证据树。
- 对历史视频重新调用 ASR/OCR；历史数据只在已有精确索引时细化，否则按分钟回退。

## Confirmed Decisions

- ASR 句级时间戳是可选能力：provider 返回可解析的句级 spans 时启用 ASR 秒级定位；不支持时 ASR 保持分钟级，不因为该能力缺失而阻断 OCR 秒级定位。
- 接受在 Agent Evidence 中新增 `anchorText`，但不向 Agent 发送完整原子证据列表。
- 精确证据索引首选复用现有 MySQL Checkpoint + Redis 热缓存，以媒体级 sidecar JSON 保存。
- 文本匹配采用严格归一化匹配：大小写、标点和空白归一化后，候选证据必须包含 `anchorText`；匹配失败回退分钟级，不做模糊猜测。
- 历史视频不重新处理。旧 Checkpoint 没有精确索引时继续使用分钟级结果。
- `ASR+OCR` 继续保留现有单条 Evidence、单个 `timestampMs` 和来源语义；不引入 `timestamps[]`，不拆成多条 Evidence。
- `ASR+OCR` 不新增跨来源 anchor 命中规则；该来源在本次改造中保持窗口级时间定位，秒级能力只作用于可单独归属的 `ASR` 或 `OCR` Evidence。
