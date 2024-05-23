package com.lcx.service.Impl;

import cn.dev33.satoken.stp.StpUtil;
import com.itextpdf.text.Document;
import com.itextpdf.text.pdf.*;
import com.lcx.common.constant.*;
import com.lcx.common.constant.Process;
import com.lcx.common.exception.process.ProcessStatusError;
import com.lcx.common.exception.process.StartCompetitionException;
import com.lcx.common.util.ConvertUtil;
import com.lcx.common.util.RedisUtil;
import com.lcx.mapper.ContestantMapper;
import com.lcx.mapper.ScoreInfoMapper;
import com.lcx.mapper.UserInfoMapper;
import com.lcx.mapper.UserMapper;
import com.lcx.pojo.Entity.Contestant;
import com.lcx.pojo.Entity.ScoreInfo;
import com.lcx.pojo.Entity.Student;
import com.lcx.pojo.Entity.UserInfo;
import com.lcx.pojo.VO.WrittenScore;
import com.lcx.pojo.VO.ScoreVo;
import com.lcx.pojo.VO.SeatInfo;
import com.lcx.pojo.VO.SignGroup;
import com.lcx.service.HostService;
import jakarta.annotation.Resource;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@Service
@Slf4j
public class HostServiceImpl implements HostService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private UserInfoMapper userInfoMapper;
    @Resource
    private ContestantMapper contestantMapper;
    @Resource
    private ScoreInfoMapper scoreInfoMapper;
    @Resource
    private UserMapper userMapper;

    // 开启比赛
    @Override
    @Transactional
    public void startCompetition() {
        // 判断报名是否已结束
        String instantStr = stringRedisTemplate.opsForValue().get(Time.SIGN_UP_END_TIME);
        if (instantStr == null) throw new StartCompetitionException(ErrorMessageConstant.START_TIME_ERROR);
        long end = Long.parseLong(instantStr);
        long now = System.currentTimeMillis();
        if (now < end) throw new StartCompetitionException(ErrorMessageConstant.START_TIME_ERROR);

        // 获得组别、赛区信息
        UserInfo userInfo = userInfoMapper.getByUid(StpUtil.getLoginIdAsInt());
        String group = userInfo.getGroup();
        String zone = userInfo.getZone();
        // 判断比赛是否已经进行
        String key = RedisUtil.getProcessKey(group, zone);
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value != null)
            throw new StartCompetitionException(ErrorMessageConstant.COMPETITION_HAS_BEGUN);

        log.info("{}:{} 比赛开始", group, zone);
        // 将比赛进程存进redis
        value = RedisUtil.getProcessValue(Process.WRITTEN, Step.SEAT_DRAW);
        stringRedisTemplate.opsForValue().set(key, value);

        //届数加一
        int session = Integer.parseInt(stringRedisTemplate.opsForValue().get("session"));
        stringRedisTemplate.opsForValue().set("session", String.valueOf(session + 1));
    }

    // 推进下一流程
    @Override
    @Transactional
    public String nextProcess() {

        // 获得当前环节
        UserInfo userInfo = userInfoMapper.getByUid(StpUtil.getLoginIdAsInt());
        String key = RedisUtil.getProcessKey(userInfo.getGroup(), userInfo.getZone());
        String value = stringRedisTemplate.opsForValue().get(key);
        // 比赛未开启
        if (value == null)
            throw new ProcessStatusError(ErrorMessageConstant.COMPETITION_HAS__NOT_BEGUN);
        String[] now = value.split(":");
        //进程错误，无法推进下一环节
        if (!now[1].equals(Step.NEXT))
            throw new ProcessStatusError(ErrorMessageConstant.PROCESS_STATUS_ERROR);

        // 获得下一环节
        String nextProcess = null, step = null;
        switch (now[0]) {
            case Process.WRITTEN -> {
                nextProcess = Process.PRACTICE;
                step = Step.GROUP_DRAW;
            }
            case Process.PRACTICE -> {
                nextProcess = Process.Q_AND_A;
                step = Step.RATE;
            }
            case Process.Q_AND_A -> {
                nextProcess = Process.FINAL;
                step = Step.SCORE_EXPORT;
            }
        }
        // 推进至下一环节
        String process = RedisUtil.getProcessValue(nextProcess, step);
        stringRedisTemplate.opsForValue().set(key, process);

        return nextProcess;
    }

    // 通过excel上传笔试成绩
    @Override
    @Transactional
    public void postWrittenScoreByExcel(MultipartFile file) {
        try {
            InputStream in = file.getInputStream();
            XSSFWorkbook excel = new XSSFWorkbook(in);
            XSSFSheet sheet = excel.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                //获得区赛成绩表
                String idCard = row.getCell(1).getStringCellValue();
                Contestant contestant = contestantMapper.getByIDCard(idCard);
                ScoreInfo scoreInfo = scoreInfoMapper.getByUid(contestant.getUid());
                //更新笔试成绩
                int writtenScore = (int) row.getCell(5).getNumericCellValue();
                scoreInfo.setWrittenScore(writtenScore);

                scoreInfoMapper.updateWrittenScore(scoreInfo);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // 座位号抽签
    @Override
    @Transactional
    public List<SeatInfo> seatDraw() {
        // 获取组别、赛区信息
        UserInfo userInfo = userInfoMapper.getByUid(StpUtil.getLoginIdAsInt());
        String group = userInfo.getGroup();
        String zone = userInfo.getZone();

        // 座位号抽签
        int count = contestantMapper.getCountByGroupAndZone(group, zone); // 组别赛区选手总数
        List<Integer> nums = new ArrayList<>();
        for (int i = 1; i <= count; i++) nums.add(i);
        Collections.shuffle(nums);

        List<Contestant> list = contestantMapper.getListByGroupAndZone(group, zone);
        List<SeatInfo> seatTable = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Contestant contestant = list.get(i);
            int session = Integer.parseInt(stringRedisTemplate.opsForValue().get("session"));
            ScoreInfo scoreInfo = ScoreInfo.builder().uid(contestant.getUid())
                    .session(session).zone(contestant.getZone()).build();
            String seatNum = group + ":" + zone + ":" + nums.get(i);
            scoreInfo.setSeatNum(seatNum);
            scoreInfoMapper.insert(scoreInfo);
            //座位信息
            SeatInfo seatInfo = SeatInfo.builder().name(contestant.getName()).seatNum(seatNum).build();
            seatTable.add(seatInfo);
        }
        //按座位号排序
        seatTable.sort(Comparator.comparingInt(o -> Integer.parseInt(o.getSeatNum().substring(6))));

        return seatTable;
    }

    // 按笔试成绩筛选
    @Override
    @Transactional
    public List<WrittenScore> scoreFilter() {
        //获得用户信息
        UserInfo userInfo = userInfoMapper.getByUid(StpUtil.getLoginIdAsInt());
        // 查询成绩单
        List<WrittenScore> scores = scoreInfoMapper
                .getVOListByGroupAndZone(userInfo.getGroup(), userInfo.getZone());
        // 按笔试成绩降序排序
        scores.sort(Comparator.comparingInt(WrittenScore::getWrittenScore).reversed());
        //人数不满30人，直接返回
        if (scores.size() <= 30) return scores;
        // 将淘汰选手的账号身份设置为游客，只能查询往年成绩
        for (int i = 30; i < scores.size(); i++) {
            WrittenScore writtenScore = scores.get(i);
            ScoreInfo scoreInfo = scoreInfoMapper.getBySeatNum(writtenScore.getSeatNum());

            userMapper.updateRole(scoreInfo.getUid(), Role.TOURIST);// 设置成游客身份
            contestantMapper.deleteByUid(scoreInfo.getUid());// 删除选手
            scoreInfoMapper.deleteByUid(scoreInfo.getUid());// 删除成绩
        }
        // 返回晋级选手成绩单
        return scores;
    }

    @Override
    @Transactional
    public List<SignGroup> groupDraw() {
        UserInfo userInfo = userInfoMapper.getByUid(StpUtil.getLoginIdAsInt());
        List<Student> students = contestantMapper
                .getStudentListByGroupAndZone(userInfo.getGroup(), userInfo.getZone());
        // 分组信息
        List<SignGroup> signGroups = groupDraw(students);
        // 更新成绩单信息
        for (SignGroup signGroup : signGroups) {
            scoreInfoMapper.updateSignNumByUid(signGroup.getA().getUid(), "A" + signGroup.getSignNum());
            scoreInfoMapper.updateSignNumByUid(signGroup.getB().getUid(), "B" + signGroup.getSignNum());
        }
        return signGroups;
    }

    @Override
    @Transactional
    public void exportScoreToPdf(HttpServletResponse response) {
        // 获得选手成绩信息
        UserInfo userInfo = userInfoMapper.getByUid(StpUtil.getLoginIdAsInt());
        String group = userInfo.getGroup();
        String zone = userInfo.getZone();
        List<ScoreVo> scoreInfoList = contestantMapper.getScoreVoListByGroupAndZone(group, zone);
        // 降序排序
        scoreInfoList.sort(Comparator.comparingDouble(ScoreVo::getFinalScore).reversed());

        // 将group,zone转换为中文
        group = ConvertUtil.parseGroupStr(group);
        zone = ConvertUtil.parseZoneStr(zone);

        InputStream in = this.getClass().getClassLoader()
                .getResourceAsStream("templates/高校编程能力大赛成绩导出模板.pdf");
        try {
            PdfReader reader = new PdfReader(in);
            ServletOutputStream out = response.getOutputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            PdfStamper stamper = new PdfStamper(reader, bos);
            AcroFields form = stamper.getAcroFields();

            // 给表单添加中文字体
            BaseFont baseFont = BaseFont.createFont(
                    "D:\\develop\\idea\\University-Programming-Competition\\src\\main\\resources\\fonts\\Deng.ttf"
                    , BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            form.addSubstitutionFont(baseFont);

            // 插入数据
            int count = 1;
            String key = "fill_";
            for (ScoreVo score : scoreInfoList) {
                form.setField(key + count, score.getName());count++;
                form.setField(key + count, group);count++;
                form.setField(key + count, zone);count++;
                form.setField(key + count, score.getSeatNum());count++;
                form.setField(key + count, String.valueOf(score.getWrittenScore()));count++;
                form.setField(key + count, String.valueOf(score.getPracticalScore()));count++;
                form.setField(key + count, String.valueOf(score.getQAndAScore()));count++;
                form.setField(key + count, String.valueOf(score.getFinalScore()));count++;
            }

            // 如果为false那么生成的PDF文件还能编辑
            stamper.setFormFlattening(true);
            stamper.close();
            // PDF文档的抽象表示
            Document doc = new Document();
            // 复制或合并PDF页面
            PdfCopy copy = new PdfCopy(doc, out);
            // 开始添加内容
            doc.open();
            // 要复制的页面
            PdfImportedPage importPage = copy.getImportedPage(new PdfReader(bos.toByteArray()), 1);
            // 将页面内容复制到最终的 PDF 文档中
            copy.addPage(importPage);

            String fileName = group + zone + "最终成绩.pdf";
            response.setContentType("application/force-download");
            response.setHeader("Content-Disposition", "attachment;fileName=" + fileName);

            doc.close();
            in.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<SignGroup> groupDraw(List<Student> students) {
        // 创建一个映射来跟踪每个学校的选手
        Map<String, List<Student>> schoolStudents = new HashMap<>();
        for (Student student : students) {
            schoolStudents.computeIfAbsent(student.getSchool(), k -> new ArrayList<>()).add(student);
        }
        int signNum = 1;
        Collections.shuffle(students);// 打乱选手顺序
        List<SignGroup> signGroups = new ArrayList<>();// 存放分组信息
        while (students.size() > 6) {
            Student A = students.remove(0);
            schoolStudents.get(A.getSchool()).remove(A);
            for (String school : new ArrayList<>(schoolStudents.keySet())) {
                if (!schoolStudents.get(school).isEmpty() && !Objects.equals(school, A.getSchool())) {
                    Student B = schoolStudents.get(school).remove(0);
                    students.remove(B);
                    signGroups.add(new SignGroup(signNum++, A, B));
                    break;
                }
            }
        }
        // 剩下6名选手随机分组可能发生同校同组的情况，需要回退
        Stack<Student> stack = new Stack<>();
        while (!students.isEmpty()) {
            Student A = students.remove(0);
            schoolStudents.get(A.getSchool()).remove(A);
            stack.push(A);
            boolean flag = true;
            for (String school : new ArrayList<>(schoolStudents.keySet())) {
                if (!schoolStudents.get(school).isEmpty() && !Objects.equals(school, A.getSchool())) {
                    Student B = schoolStudents.get(school).remove(0);
                    students.remove(B);
                    stack.push(B);
                    signGroups.add(new SignGroup(signNum++, A, B));
                    flag = false;
                    break;
                }
            }
            // 剩下两名选手来自同一学校，回退两步
            if (flag) {
                students.addAll(stack);
                schoolStudents.get(stack.peek().getSchool()).add(stack.pop());
                schoolStudents.get(stack.peek().getSchool()).add(stack.pop());
                schoolStudents.get(stack.peek().getSchool()).add(stack.pop());
                schoolStudents.get(stack.peek().getSchool()).add(stack.pop());
                signGroups.remove(signGroups.size() - 1);
                signGroups.remove(signGroups.size() - 1);
                signNum -= 2;
            }
        }
        return signGroups;
    }

}