package com.lcx.service.Impl;

import com.alibaba.fastjson2.JSON;
import com.itextpdf.text.Document;
import com.itextpdf.text.pdf.*;
import com.lcx.common.constant.*;
import com.lcx.common.constant.Process;
import com.lcx.common.exception.process.ProcessStatusException;
import com.lcx.common.exception.process.StartCompetitionException;
import com.lcx.common.utils.ConvertUtil;
import com.lcx.common.utils.RedisUtil;
import com.lcx.domain.Entity.Contestant;
import com.lcx.domain.Entity.ScoreInfo;
import com.lcx.domain.Entity.SingleScore;
import com.lcx.domain.Entity.Student;
import com.lcx.domain.VO.FinalSingleScore;
import com.lcx.domain.VO.GroupScore;
import com.lcx.domain.VO.GradeVO;
import com.lcx.domain.VO.SeatInfo;
import com.lcx.domain.VO.SignGroup;
import com.lcx.mapper.*;

import com.lcx.service.HostService;
import com.lcx.taskSchedule.AutoBackupsService;
import jakarta.annotation.Resource;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFRow;
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
    private ContestantMapper contestantMapper;
    @Resource
    private ScoreInfoMapper scoreInfoMapper;
    @Resource
    private WrittenScoreMapper writtenScoreMapper;
    @Resource
    private SchoolMapper schoolMapper;
    @Resource
    private AutoBackupsService autoBackupsService;

    // 开启区赛
    @Override
    @Transactional
    public void startCompetition(String group, String zone) {
        // 判断报名是否已结束
        String instantStr = stringRedisTemplate.opsForValue().get(Time.SIGN_UP_END_TIME);
        if (instantStr == null) throw new StartCompetitionException(ErrorMessage.START_TIME_ERROR);
        long end = Long.parseLong(instantStr);
        long now = System.currentTimeMillis();
        if (now < end) throw new StartCompetitionException(ErrorMessage.START_TIME_ERROR);

        // 判断比赛是否已经进行
        String key = RedisUtil.getProcessKey(group, zone);
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value != null)
            throw new StartCompetitionException(ErrorMessage.COMPETITION_HAS_BEGUN);

        // 重置学校选手数量
        schoolMapper.resetNum(group,zone);

        // 插入成绩信息
        insertScoreInfo(group, zone);

        log.info("{}:{} 比赛开始", group, zone);
        // 将比赛进程存进redis
        value = RedisUtil.getProcessValue(Process.WRITTEN, Step.SEAT_DRAW);
        stringRedisTemplate.opsForValue().set(key, value);

    }

    // 推进下一流程
    @Override
    @Transactional
    public String nextProcess(String group, String zone) {

        // 获得当前环节
        String key = RedisUtil.getProcessKey(group, zone);
        String value = stringRedisTemplate.opsForValue().get(key);
        // 比赛未开启
        if (value == null)
            throw new ProcessStatusException(ErrorMessage.COMPETITION_HAS__NOT_BEGUN);
        String[] now = value.split(":");
        //进程错误，无法推进下一环节
        if (!now[1].equals(Step.NEXT))
            throw new ProcessStatusException(ErrorMessage.PROCESS_STATUS_ERROR);

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

        if(Objects.equals(step, Step.RATE)){
            // 关闭每天00：00自动备份数据库
            autoBackupsService.StopAutoBackups();
            // 每一小时备份一次
            autoBackupsService.StartAutoBackups("0 0 0/1 * * ?");
        }

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
    public List<SeatInfo> seatDraw(String group, String zone) {

        // 座位号抽签
        int count = contestantMapper.getCountByGroupAndZone(group, zone); // 组别赛区选手总数
        List<Integer> nums = new ArrayList<>();
        for (int i = 1; i <= count; i++) nums.add(i);
        Collections.shuffle(nums);

        List<Contestant> list = contestantMapper.getListByGroupAndZone(group, zone);
        List<SeatInfo> seatTable = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Contestant contestant = list.get(i);
            String seatNum = group + ":" + zone + ":" + nums.get(i);
            scoreInfoMapper.updateSeatNum(contestant.getUid(), seatNum);
            //座位信息
            SeatInfo seatInfo = SeatInfo.builder().name(contestant.getName()).seatNum(seatNum).build();
            seatTable.add(seatInfo);
        }
        //按座位号升序排序
        seatTable.sort(Comparator.comparingInt(o -> Integer.parseInt(
                o.getSeatNum().substring(o.getSeatNum().lastIndexOf(":") + 1))));

        return seatTable;
    }

    // 按笔试成绩筛选
    @Override
    @Transactional
    public List<SingleScore> scoreFilter(String group, String zone) {
        // 查询成绩单
        List<SingleScore> scores = scoreInfoMapper.getWrittenScoreList(group, zone);
        // 按笔试成绩降序排序
        scores.sort(Comparator.comparingInt(SingleScore::getScore).reversed());

        // 添加到written_score表
        for (int i = 0; i < scores.size(); i++) {
            SingleScore singleScore = scores.get(i);
            singleScore.setRanking(i + 1);
            writtenScoreMapper.insert(singleScore);
        }

        //人数不满30人，直接返回
        if (scores.size() <= 30) return scores;
        // 将淘汰选手的账号身份设置为游客，只能查询往年成绩、笔试成绩
        for (int i = 30; i < scores.size(); i++) {
            int uid = scores.get(i).getUid();

            contestantMapper.deleteByUid(uid);// 删除选手
            scoreInfoMapper.deleteByUid(uid);// 删除成绩
        }
        // 返回晋级选手成绩单
        return scores;
    }

    // 分组抽签
    @Override
    @Transactional
    //@Async("asyncServiceExecutor")
    public List<SignGroup> groupDraw(String group, String zone) {
        List<Student> students = contestantMapper.getStudentListByGroupAndZone(group, zone);
        // 分组信息
        List<SignGroup> signGroups = groupDraw(students);

        // 序列化
        String jasonStr = JSON.toJSONString(signGroups);
        String key = RedisUtil.getSignGroupsKey(group, zone);
        // 将分组信息存进redis
        stringRedisTemplate.opsForValue().set(key, jasonStr);

        // 更新成绩信息
        for (SignGroup signGroup : signGroups) {
            scoreInfoMapper.updateSignNumByUid(signGroup.getA().getUid(), "A" + signGroup.getSignNum());
            scoreInfoMapper.updateSignNumByUid(signGroup.getB().getUid(), "B" + signGroup.getSignNum());
        }
        return signGroups;
    }

    //导出成绩
    @Override
    @Transactional
    public void exportScoreToPdf(String group, String zone, HttpServletResponse response) {
        List<GradeVO> scoreInfoList = contestantMapper.getScoreVoListByGroupAndZone(group, zone);
        // 降序排序
        scoreInfoList.sort(Comparator.comparingDouble(GradeVO::getFinalScore).reversed());

        // 区赛为true
        // 国赛为false
        boolean flag = !Objects.equals(zone, Zone.N);

        // 将group,zone转换为中文
        group = ConvertUtil.parseGroupStr(group);
        zone = ConvertUtil.parseZoneStr(zone);

        InputStream in;
        if(flag){
            in= this.getClass().getClassLoader().getResourceAsStream("templates/高校编程能力大赛区赛成绩导出模板.pdf");
        }else{
            in = this.getClass().getClassLoader().getResourceAsStream("templates/高校编程能力大赛国赛成绩导出模板.pdf");
        }

        try {
            assert in != null;
            PdfReader reader = new PdfReader(in);
            ServletOutputStream out = response.getOutputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            PdfStamper stamper = new PdfStamper(reader, bos);
            AcroFields form = stamper.getAcroFields();

            // 给表单添加中文字体
            BaseFont baseFont = BaseFont.createFont(
                    "src/main/resources/fonts/Deng.ttf"
                    , BaseFont.IDENTITY_H, BaseFont.NOT_EMBEDDED);
            form.addSubstitutionFont(baseFont);

//            for (String k : form.getFields().keySet())
//                form.setFieldProperty(k, "deng", baseFont, null);

            // 插入数据
            int i = 1;
            String key = "fill_";
            if(flag){
                for (GradeVO score : scoreInfoList) {
                    form.setField(key + i, score.getName());i++;
                    form.setField(key + i, group);i++;
                    form.setField(key + i, zone);i++;
                    form.setField(key + i, score.getSeatNum());i++;
                    form.setField(key + i, String.valueOf(score.getWrittenScore()));i++;
                    form.setField(key + i, String.valueOf(score.getPracticalScore()));i++;
                    form.setField(key + i, String.valueOf(score.getQAndAScore()));i++;
                    form.setField(key + i, String.valueOf(score.getFinalScore()));i++;
                }
            }else{
                for (GradeVO score : scoreInfoList) {
                    form.setField(key + i, score.getName());i++;
                    form.setField(key + i, group);i++;
                    form.setField(key + i, zone);i++;
                    form.setField(key + i, String.valueOf(score.getPracticalScore()));i++;
                    form.setField(key + i, String.valueOf(score.getQAndAScore()));i++;
                    form.setField(key + i, String.valueOf(score.getFinalScore()));i++;
                }
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

    // 分组抽签算法
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

    // 插入成绩信息
    @Override
    public void insertScoreInfo(String group, String zone) {
        List<Integer> uidList = contestantMapper.getUidListByGroupAndZone(group, zone);
        for (Integer uid : uidList) {
            String session = stringRedisTemplate.opsForValue().get("session");
            ScoreInfo scoreInfo = ScoreInfo.builder().uid(uid).group(group)
                    .zone(zone).session(session).build();
            scoreInfoMapper.insert(scoreInfo);
        }
    }

    // 查询实战对决分数
    @Override
    public GroupScore getGroupScore(int aUid, int bUid) {
        FinalSingleScore A = scoreInfoMapper.getPracticalScoreByUid(aUid);
        FinalSingleScore B = scoreInfoMapper.getPracticalScoreByUid(bUid);
        return GroupScore.builder().A(A).B(B).build();
    }

    // 查询快问快答分数
    @Override
    public FinalSingleScore getQAndAScore(int uid) {
        return scoreInfoMapper.getQAndAScoreByUid(uid);
    }

    @Override
    public void getExcelTemplate(HttpServletResponse response){
        XSSFWorkbook excelTemplate = new XSSFWorkbook();
        XSSFSheet sheet = excelTemplate.createSheet("笔试成绩");

        XSSFRow row = sheet.createRow(0);
        row.createCell(0).setCellValue("姓名");
        row.createCell(1).setCellValue("身份证号码");
        row.createCell(2).setCellValue("参数组别");
        row.createCell(3).setCellValue("赛区");
        row.createCell(4).setCellValue("座位号");
        row.createCell(5).setCellValue("分数");

        response.setContentType("application/vnd.ms-excel");
        response.setHeader("Content-Disposition", "attachment; filename=template.xlsx");
        ServletOutputStream out;
        try {
            out = response.getOutputStream();
            excelTemplate.write(out);
            excelTemplate.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}