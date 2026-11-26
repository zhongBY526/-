package com.intern.backed.document;

import com.intern.backed.auth.User;
import com.intern.backed.common.ForbiddenException;
import com.intern.backed.kb.KbAccessService;
import com.intern.backed.kb.KnowledgeBase;
import com.intern.backed.retrieval.MetadataKeys;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
public class DocumentService {
    private static final Set<String> ALLOWED_EXT = Set.of("pdf", "doc", "docx", "md", "txt");
    private final DocumentRepository documentRepository;
    private final IngestionService ingestionService;
    private final EmbeddingStore<TextSegment>embeddingStore;
    private final KbAccessService kbAccessService;
    public DocumentService(DocumentRepository documentRepository , IngestionService ingestionService, EmbeddingStore<TextSegment> embeddingStore, KbAccessService kbAccessService) {
        this.documentRepository = documentRepository;
        this.ingestionService = ingestionService;
        this.kbAccessService = kbAccessService;
        this.embeddingStore = embeddingStore;
    }
    public RagDocument upload(User user, long kbId, MultipartFile file) {
        kbAccessService.requireAccess(user, kbId);
        String filename = file.getOriginalFilename();
        if(file.isEmpty()){
            throw new IllegalArgumentException("File is empty or filename is null");
        }
        filename=filename.replace("\\","/");
        String ext = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
        if (!ALLOWED_EXT.contains(ext)) {
            throw new IllegalArgumentException("Unsupported file type: " + ext);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } long docId = documentRepository.insert(kbId, filename, ext, file.getSize(), user.id());
        ingestionService.ingest(docId, kbId, filename, bytes);
        return documentRepository.findById(docId).orElseThrow();
    }
    public List<RagDocument> list(User user, long kbId) {
        kbAccessService.requireAccess(user, kbId);
        return documentRepository.findByKbId(kbId);
    }
    public void delete(User user,long docId){
        RagDocument document=documentRepository.findById(docId)
                .orElseThrow(()->new ForbiddenException("文档不存在"));
        KnowledgeBase kb=kbAccessService.requireAccess(user, document.kbId());
        if(!user.isAdmin() && kb.ownerId() != user.id() && document.uploadedBy() != user.id()){
            throw new ForbiddenException("无权限删除文档");
        }
        embeddingStore.removeAll(metadataKey(MetadataKeys.DOC_ID).isEqualTo(String.valueOf(docId)));
        documentRepository.deleteById(docId);
    }
}
