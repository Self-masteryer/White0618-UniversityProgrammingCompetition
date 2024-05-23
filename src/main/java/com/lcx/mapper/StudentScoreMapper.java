package com.lcx.mapper;

import com.lcx.pojo.Entity.StudentScore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StudentScoreMapper {

    @Insert("insert into student_score (name, school, id_card, session, score, prize) " +
            "value (#{name},#{school},#{idCard},#{session},#{score},#{prize})")
    void insert(StudentScore studentScore);

}
