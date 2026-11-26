package com.intern.backed.kb;

import com.intern.backed.auth.User;
import com.intern.backed.common.ForbiddenException;
import org.springframework.stereotype.Service;

import java.util.List;
//判断用户是否可访问该知识库
@Service
public class KbAccessService {
    private final KbRepository kbRepository;
    public KbAccessService(KbRepository kbRepository) {
        this.kbRepository = kbRepository;
    }
    // 可访问范围：本人创建 + 公开 + 同部门共享；ADMIN 全量
    // 查询用户可访问的知识库列表
    public List<KnowledgeBase> accessibleKbs(User user) {
        return kbRepository.findAll().stream()
                .filter(kb -> canAccess(user, kb))
                .toList();
    }
    // 查询用户可访问的知识库 ID 列表
    public List<Long> accessibleKbIds(User user) {
        return accessibleKbs(user).stream().map(KnowledgeBase::id).toList();
    }
    // 判断用户是否可访问该知识库，用于访问控制
    public boolean canAccess(User user, KnowledgeBase kb) {
        if (user.isAdmin() || kb.ownerId() == user.id()) {
            return true;
        }
        return switch (kb.visibility()) {
            case KnowledgeBase.PUBLIC -> true;
            case KnowledgeBase.DEPARTMENT -> kb.department().equals(user.department());
            default -> false;
        };
    }
    // 访问控制，无权访问抛 ForbiddenException，用于访问知识库时的权限检查
    public KnowledgeBase requireAccess(User user, long kbId) {
        KnowledgeBase kb = kbRepository.findById(kbId)
                .orElseThrow(() -> new ForbiddenException("知识库不存在或无权访问"));
        if (!canAccess(user, kb)) {
            throw new ForbiddenException("无权访问该知识库");
        }
        return kb;
    }
    // 修改/删除仅允许所有者或管理员，用于知识库的修改和删除操作
    public KnowledgeBase requireManage(User user, long kbId) {
        KnowledgeBase kb = kbRepository.findById(kbId)
                .orElseThrow(() -> new ForbiddenException("知识库不存在或无权管理"));
        if (!user.isAdmin() && kb.ownerId() != user.id()) {
            throw new ForbiddenException("仅知识库所有者或管理员可执行此操作");
        }
        return kb;
    }
}