package com.intern.backed.document;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class DocumentRepository {
    private static final RowMapper<RagDocument> MAPPER = (rs, i) -> new RagDocument(
            rs.getLong("id"), rs.getLong("kb_id"), rs.getString("file_name"),
            rs.getString("file_type"), rs.getLong("file_size"), rs.getString("status"),
            rs.getString("error_msg"), rs.getInt("chunk_count"), rs.getLong("uploaded_by"),
            rs.getTimestamp("created_at").toLocalDateTime());
    private final JdbcTemplate jdbc;
    public DocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
    public long insert(long kbId, String fileName, String fileType, long fileSize, long uploadedBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO rag_document(kb_id, file_name, file_type, file_size, status, uploaded_by) VALUES (?, ?, ?, ?, ?, ?)",
                    // PG 驱动对 RETURN_GENERATED_KEYS 会返回整行，getKey() 遇多列抛异常，须显式指定主键列
                    new String[]{"id"});
            ps.setLong(1, kbId);
            ps.setString(2, fileName);
            ps.setString(3, fileType);
            ps.setLong(4, fileSize);
            ps.setString(5, RagDocument.PROCESSING);
            ps.setLong(6, uploadedBy);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }
    //通过知识库ID查询文档
    public List<RagDocument> findByKbId(long kbId) {
        return jdbc.query("SELECT * FROM rag_document WHERE kb_id = ? ORDER BY id DESC", MAPPER, kbId);
    }
//通过ID查询文档
    public Optional<RagDocument> findById(long id) {
        return jdbc.query("SELECT * FROM rag_document WHERE id = ?", MAPPER, id).stream().findFirst();
    }
//通过ID标记文档为就绪状态
    public void markReady(long id, int chunkCount) {
        jdbc.update("UPDATE rag_document SET status = ?, chunk_count = ?, error_msg = NULL WHERE id = ?",
                RagDocument.READY, chunkCount, id);
    }
//通过ID标记文档为失败状态
    public void markFailed(long id, String errorMsg) {
        jdbc.update("UPDATE rag_document SET status = ?, error_msg = ? WHERE id = ?",
                RagDocument.FAILED, errorMsg, id);
    }
//通过ID删除文档
    public void deleteById(long id) {
        jdbc.update("DELETE FROM rag_document WHERE id = ?", id);
    }
}
