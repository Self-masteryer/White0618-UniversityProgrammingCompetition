package com.lcx.mapper;

import com.lcx.pojo.Entity.Score;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PracticalScoreMapper {

    @Insert("insert into practical_score (uid, sid, jid, score) " +
            "value (#{uid},#{sid},#{jid},#{score})")
    void insert(Score score);

    @Select("select count(id) from practical_score where uid=#{uid}")
    int getCountByUid(int uid);

    @Select("select score from practical_score where uid=#{uid}")
    List<Integer> getScoresByUid(int uid);
}
