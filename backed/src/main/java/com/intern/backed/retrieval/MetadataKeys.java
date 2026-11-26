package com.intern.backed.retrieval;

// 向量段元数据键，入库/检索/删除三方共用，值统一存字符串
public final class MetadataKeys {
    public static final String KB_ID = "kb_id";
    public static final String DOC_ID = "doc_id";
    public static final String FILE_NAME = "file_name";
    public static final String CHUNK_INDEX = "chunk_index";

    private MetadataKeys() {
    }
}