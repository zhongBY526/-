package com.intern.backed.retrieval;

import java.util.Map;

public record RetrievalChunk(String text,
                             Map<String,String> metadata,
                             double score,
                             ScoreType scoreType) {

    /** score 的口径：不同链路产出的分数不可直接比较，展示时必须区分 */
    public enum ScoreType {
        /** 向量检索的余弦相似度，0~1，越大越相关 */
        VECTOR,
        /** 重排序模型给出的相关性分，模型原始分，仅同一次查询内可比 */
        RERANK,
        /** RRF 融合排名分 1/(60+rank)，量级约 0.01x，只表示名次不表示相关度 */
        RRF
    }
}