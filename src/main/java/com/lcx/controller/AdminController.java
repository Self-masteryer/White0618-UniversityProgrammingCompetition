package com.lcx.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.lcx.common.result.PageResult;
import com.lcx.common.result.Result;
import com.lcx.domain.DTO.*;
import com.lcx.domain.VO.FinalSingleScore;
import com.lcx.domain.VO.ProcessVO;
import com.lcx.domain.VO.SingeScoreInfo;
import com.lcx.service.AdminService;
import com.lcx.service.ScoreService;
import com.lcx.taskSchedule.AutoBackupsService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/admin")
@SaCheckRole("admin")
@Slf4j
public class AdminController {

    @Resource
    private AdminService adminService;
    @Resource
    private ScoreService scoreService;
    @Resource
    private AutoBackupsService autoBackupsService;

    // 通过excel表格新增用户
    @PostMapping("/createUserByExcel")
    public void createUserByExcel(@RequestParam("file") MultipartFile file, HttpServletResponse response) {
        adminService.addUserByExcel(file, response);
        log.info("管理员通过excel表格设置管理员、裁判");
    }

    // 通过excel表格新增学校用户
    @PostMapping("/createSchoolByExcel")
    public void createSchoolByExcel(@RequestParam("file") MultipartFile file, HttpServletResponse response) {
        adminService.addSchoolByExcel(file, response);
        log.info("管理员通过excel表格新增学校用户");
    }

    // 设置报名时间
    @PostMapping("/setSignUpTime")
    public Result setSignUpTime(@RequestBody TimePeriod timePeriod) {
        adminService.setSignUpTime(timePeriod);
        // 启动数据库自动备份 每天00：00：00
        autoBackupsService.StartAutoBackups("0 0 0 * * ? ");
        log.info("设置报名时间:{}~{}", timePeriod.getBegin(), timePeriod.getEnd());
        return Result.success("报名时间设置成功");
    }

    // 修改放弃国赛资格时间
    @PutMapping("/setWaiverNatQualTime")
    public Result setWaiverNatQualTime(@RequestBody TimePeriod timePeriod) {
        adminService.setWaiverNatQualTime(timePeriod);
        log.info("成功修改放弃国赛资格时间段:{}~{}",timePeriod.getBegin(), timePeriod.getEnd());
        return Result.success();
    }

    // 开启国赛
    @GetMapping("/startNationalCompetition")
    public Result startNationalCompetition() {
        adminService.startNationalCompetition();
        log.info("管理员已开启国赛");
        return Result.success();
    }

    // 分页查询往届成绩
    @GetMapping("/preGrade")
    public Result<PageResult> queryPreScore(PreScorePageQuery preScorePageQuery) {
        return Result.success(scoreService.queryPreScore(preScorePageQuery));
    }

    // 查询往届学生获奖情况
    @GetMapping("/studentScore")
    public Result<PageResult> pageQueryStudentScore(StudentScorePageQuery studentScorePageQuery) {
        return Result.success(scoreService.pageQueryStudentScore(studentScorePageQuery));
    }

    // 查询赛区进程
    @GetMapping("/process")
    public Result<List<ProcessVO>> queryProcess(String group,String zone) {
        return Result.success(adminService.queryProcess(group,zone));
    }

    // 查询账号状态
    @GetMapping("/status")
    public Result<PageResult> queryStatus(StatusPageQuery statusPageQuery) {
        return Result.success(adminService.queryStatus(statusPageQuery));
    }

    // 查询实战环节评委打分情况、选手得分情况
    @GetMapping("/practicalScoreInfo")
    public Result<List<SingeScoreInfo>> queryPracticalScoreInfo(ScoreInfoQuery scoreInfoQuery) {
        return Result.success(adminService.queryPracticalScoreInfo(scoreInfoQuery));
    }

    // 查询实战环节选手最终得分情况
    @GetMapping("/practicalScore")
    public Result<List<FinalSingleScore>> queryPracticalScore(ScoreQuery scoreQuery) {
        return Result.success(adminService.queryPracticalScore(scoreQuery));
    }

    // 查询快问快答环节评委打分情况、选手得分情况
    @GetMapping("/qAndAScoreInfo")
    public Result<List<SingeScoreInfo>> queryQAndAScoreInfo(ScoreInfoQuery scoreInfoQuery) {
        return Result.success(adminService.queryqAndAScoreInfo(scoreInfoQuery));
    }

    // 查询快问快答环节选手最终得分情况
    @GetMapping("/qAndAScore")
    public Result<List<FinalSingleScore>> queryQAndAScore(ScoreQuery scoreQuery) {
        return Result.success(adminService.queryqAndAScore(scoreQuery));
    }

}
