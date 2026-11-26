package com.intern.backed.kb;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
// 知识库的仓库类,使用jdbc操作数据库
@Repository
public class KbRepository {
    private static final RowMapper<KnowledgeBase> MAPPER = (rs, i) -> new KnowledgeBase(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getLong("owner_id"),
            rs.getString("department"),
            rs.getString("visibility"),
            rs.getTimestamp("created_at").toLocalDateTime());
    private final JdbcTemplate jdbc;
    public KbRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
    // 查询所有知识库,按id排序
    public List<KnowledgeBase> findAll() {
        return jdbc.query("SELECT * FROM knowledge_base ORDER BY id", MAPPER);
    }
    // 根据id查询知识库,如果不存在则返回空
    public Optional<KnowledgeBase> findById(long id) {
        return jdbc.query("SELECT * FROM knowledge_base WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }
    // 插入新知识库,返回插入记录的id
    public long insert(String name, String description, long ownerId, String department, String visibility) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO knowledge_base(name, description, owner_id, department, visibility) VALUES (?, ?, ?, ?, ?)",
                    // PG 驱动对 RETURN_GENERATED_KEYS 会返回整行，getKey() 遇多列抛异常，须显式指定主键列
                    new String[]{"id"});
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setLong(3, ownerId);
            ps.setString(4, department);
            ps.setString(5, visibility);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }
    // 更新知识库信息
    // 更新知识库信息,更新name、description、visibility字段,根据id定位

    public void update(long id, String name, String description, String visibility) {
        jdbc.update("UPDATE knowledge_base SET name = ?, description = ?, visibility = ? WHERE id = ?",
                name, description, visibility, id);
    }
    // 根据id删除知识库
    public void deleteById(long id) {
        jdbc.update("DELETE FROM knowledge_base WHERE id = ?", id);
    }
}