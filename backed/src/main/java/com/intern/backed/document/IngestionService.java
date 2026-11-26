package com.intern.backed.document;

import com.intern.backed.retrieval.MetadataKeys;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.List;
@Slf4j
@Service
public class IngestionService {
    private final DocumentRepository documentRepository;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final int chunkSize;
    private final int chunkOverlap;
    public IngestionService(DocumentRepository documentRepository,
                            EmbeddingModel embeddingModel,
                            EmbeddingStore<TextSegment> embeddingStore,
                            @Value("${rag.ingestion.chunk-size}") int chunkSize,
                            @Value("${rag.ingestion.chunk-overlap}") int chunkOverlap) {
        this.documentRepository = documentRepository;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }
    //文档入库
    @Async("ingestionExecutor")
    public void ingest(long docId, long kbId, String fileName, byte[] content){
        try {
            Document document = parser(fileName, content);
            document.metadata()
                    .put(MetadataKeys.KB_ID, String.valueOf(kbId))
                    .put(MetadataKeys.DOC_ID, String.valueOf(docId))
                    .put(MetadataKeys.FILE_NAME, fileName);
            DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
            List<TextSegment> segments = splitter.split(document);
            if (segments == null || segments.isEmpty()) {
                documentRepository.markFailed(docId, "文档内容为空或解析失败");
                return ;
            }
            for (int i = 0; i < segments.size(); i++) {
                segments.get(i).metadata().put(MetadataKeys.CHUNK_INDEX, String.valueOf(i));
            }
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            embeddingStore.addAll(embeddings, segments);
            documentRepository.markReady(docId, segments.size());
            log.info("文档入库完成 docId={}, chunks={}", docId, segments.size());
        }catch(Exception e){
            log.error("文档入库失败 docId={}", docId, e);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // error_msg 列宽 1024，截断防二次报错
            documentRepository.markFailed(docId, msg.length() > 1000 ? msg.substring(0, 1000) : msg);
        }
    }
    //文档解析器
    private Document parser(String filename, byte[] content){
        String ext=filename.substring(filename.lastIndexOf(".")+1).toLowerCase();
        DocumentParser parser=switch(ext){
            case "pdf" -> new ApachePdfBoxDocumentParser();
            case "docx","doc" -> new ApachePoiDocumentParser();
            case "txt","md" -> new TextDocumentParser();

            default -> throw new IllegalArgumentException("Unsupported file type: " + ext);
        };
        return parser.parse(new ByteArrayInputStream(content));
    }
}
