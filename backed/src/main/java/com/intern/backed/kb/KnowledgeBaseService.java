package com.intern.backed.kb;
import com.intern.backed.auth.User;
import com.intern.backed.dto.KbRequest;
import com.intern.backed.retrieval.MetadataKeys;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Service;
import java.util.List;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
// 知识库服务类
@Service
public class KnowledgeBaseService {
    private final KbRepository kbRepository;
    private final KbAccessService accessService;
    private final EmbeddingStore<TextSegment> embeddingStore;
    public KnowledgeBaseService(KbRepository kbRepository, KbAccessService accessService,
                                EmbeddingStore<TextSegment> embeddingStore) {
        this.kbRepository = kbRepository;
        this.accessService = accessService;
        this.embeddingStore = embeddingStore;
    }
    // 查询用户可访问的知识库列表
    public List<KnowledgeBase> list(User user) {
        return accessService.accessibleKbs(user);
    }
    // 创建知识库
    public KnowledgeBase create(User user, KbRequest request) {
        String visibility = request.visibility() == null ? KnowledgeBase.PRIVATE : request.visibility();
        String description = request.description() == null ? "" : request.description();
        long id = kbRepository.insert(request.name(), description, user.id(), user.department(), visibility);
        return kbRepository.findById(id).orElseThrow();
    // 更新知识库
    }
    public KnowledgeBase update(User user, long kbId, KbRequest request) {
        accessService.requireManage(user, kbId);
        String visibility = request.visibility() == null ? KnowledgeBase.PRIVATE : request.visibility();
        String description = request.description() == null ? "" : request.description();
        kbRepository.update(kbId, request.name(), description, visibility);
        return kbRepository.findById(kbId).orElseThrow();
    }
    // 删除知识库
    public void delete(User user, long kbId) {
        accessService.requireManage(user, kbId);
        // 先清向量再删业务记录（rag_document 由外键级联删除），避免留下孤儿向量
        embeddingStore.removeAll(metadataKey(MetadataKeys.KB_ID).isEqualTo(String.valueOf(kbId)));
        kbRepository.deleteById(kbId);
    }
}