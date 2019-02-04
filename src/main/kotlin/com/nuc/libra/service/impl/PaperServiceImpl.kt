package com.nuc.libra.service.impl


import com.nuc.libra.entity.result.Json
import com.nuc.libra.exception.ResultException
import com.nuc.libra.po.ClassAndPages
import com.nuc.libra.po.StudentAnswer
import com.nuc.libra.po.StudentScore
import com.nuc.libra.po.Title
import com.nuc.libra.repository.*
import com.nuc.libra.service.PaperService
import com.nuc.libra.util.NLPUtils
import com.nuc.libra.vo.AnsVO
import com.nuc.libra.vo.StudentAnswerSelect
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.annotation.RabbitHandler
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.sql.Date
import java.sql.Timestamp
import javax.transaction.Transactional

/**
 * @author 杨晓辉 2018/2/3 16:04
 * @Version 1.0
 */
@Service
class PaperServiceImpl : PaperService {

    private val logger: Logger = LoggerFactory.getLogger(this.javaClass)

    @Autowired
    private lateinit var classAndPagesRepository: ClassAndPagesRepository

    @Autowired
    private lateinit var pagesRepository: PagesRepository

    @Autowired
    private lateinit var pagesAndTitleRepository: PageAndTitleRepository

    @Autowired
    private lateinit var studentAnswerRepository: StudentAnswerRepository

    @Autowired
    private lateinit var studentScoreRepository: StudentScoreRepository

    @Autowired
    private lateinit var titleRepository: TitleRepository


    /**
     * 获取该班级的所有考试
     * @param classId 班级id
     * @return list 班级考试试卷和考试名称
     */
    override fun listClassPage(classId: Long): List<ClassAndPages> {
        val classAndPages = classAndPagesRepository.findByClassId(classId)
        if (classAndPages.isNullOrEmpty()) {
            throw ResultException("没有该班级/该班级没有考试", 500)
        }
        return classAndPages
    }

    /**
     * 获取指定考试的试题
     * @param pageId 试卷id
     * @param classId 班级id
     * @return 返回试卷试题
     */
    @Transactional
    override fun getOnePage(classId: Long, pageId: Long): List<Title> {
        val classAndPages = classAndPagesRepository.findByPagesIdAndClassId(pageId, classId).toList()
        if (classAndPages.isEmpty()) {
            throw ResultException("没有该考试", 500)
        }
        val nowTime = Timestamp(System.currentTimeMillis())
        if (nowTime.after(classAndPages[0].endTime) || nowTime.before(classAndPages[0].startTime)) {
            throw ResultException("该时间段内没有该考试", 500)
        }
        val pagesAndTitleList = pagesAndTitleRepository.findByPagesId(pageId)
        return pagesAndTitleList.map {
            //            logger.info("page is ${it.pagesId}")
            titleRepository.findById(it.titleId).get()
        }

    }

    /**
     *
     * 通过 监听 rabbitMQ， 进行判题
     * @param `json` 前端字符串
     */
    @Transactional
    @RabbitListener(queues = ["check"])
    @RabbitHandler
    fun addPages(result: Json) {
        logger.info("result json :: $result")
        val ansListInDb =
            studentAnswerRepository.findByStudentIdAndPagesId(result.result.studentId, result.result.pageId)
        if (ansListInDb.isNotEmpty()) {
            return
        }
        val ansList = ArrayList<StudentAnswer>()

        for (studentAns in result.result.answer) {
            val standardAnswer = titleRepository.findById(studentAns.id).get()
            val studentAnswer = StudentAnswer()
            studentAnswer.pagesId = result.result.pageId
            studentAnswer.studentId = result.result.studentId
            studentAnswer.time = Timestamp(System.currentTimeMillis())
            studentAnswer.score = 0.0
            studentAnswer.answer = studentAns.ans
            studentAnswer.titleId = studentAns.id
            val isOrder = standardAnswer.orderd
            when (standardAnswer.category) {
                // 单选题
                "1" -> {
                    studentAnswer.score = checkChoice(studentAns.ans, standardAnswer.answer, 5.0)
                    ansList.add(studentAnswer)
                }
                // 填空题
                "2" -> {
                    logger.info("填空题")
                    studentAnswer.score = checkBlank(studentAns.ans, standardAnswer.answer, 5.0, isOrder)
                    ansList.add(studentAnswer)
                }
                // 简答题
                "3" -> {
                    val ansTitleScore = 10.0
                    logger.info("解答题")
                    studentAnswer.score = checkQuestion(studentAns.ans, standardAnswer.answer, ansTitleScore)
                    ansList.add(studentAnswer)
                }
                else -> {
                    studentAnswer.score = 0.0
                    ansList.add(studentAnswer)
                }
            }
        }

        val titleList = pagesAndTitleRepository.findByPagesId(result.result.pageId)
        val titleId = ArrayList<Long>()
        titleList.forEach {
            titleId.add(it.titleId)
        }
        val studentTitleId = ArrayList<Long>()
        ansList.forEach {
            studentTitleId.add(it.titleId)
        }
        titleId.removeAll(studentTitleId)
        for (i in 0 until titleId.size) {
            val ans = StudentAnswer()
            ans.titleId = titleId[i]
            ans.answer = ""
            ans.score = 0.0
            ans.pagesId = result.result.pageId
            ans.studentId = result.result.studentId
            ans.time = Timestamp(System.currentTimeMillis())
            ansList.add(ans)
        }

        studentAnswerRepository.saveAll(ansList)
        // 2019年2月4日 为什么要存了再去取？ 不矛盾吗？ 为什么不直接算分数
        // 计算总分
        val scoreList =
            studentAnswerRepository.findByStudentIdAndPagesId(result.result.studentId, result.result.pageId)

        var sumScore = 0.0
        scoreList.forEach {
            sumScore += it.score
        }

        val studentScore = StudentScore()
        studentScore.pagesId = result.result.pageId
        studentScore.studentId = result.result.studentId
        studentScore.status = "1"
        studentScore.score = sumScore
        studentScore.time = Timestamp(System.currentTimeMillis())
        studentScore.dotime = Date(System.currentTimeMillis())
        studentScoreRepository.save(studentScore)
    }

    /**
     * 试卷校验
     * @param studentId 学生id
     * @param pageId 试卷id
     */
    override fun verifyPage(studentId: Long, pageId: Long) {

        val ansList = studentAnswerRepository.findByStudentIdAndPagesId(
            studentId,
            pageId
        )
        if (ansList.isNotEmpty()) {
            throw ResultException("你已经提交过答案，请勿重复提交", 500)
        }
    }


    /**
     * 通过id 获取该学生的所有成绩
     * @param studentId 学生id
     * @return list 返回该学生所有的分数
     */
    override fun listScore(studentId: Long): List<StudentScore> {
        return studentScoreRepository.findByStudentId(studentId)
                ?: throw ResultException("你还没有参加考试", 500)
    }

    /**
     * 查看单张试卷考试成绩
     *
     * 戊戌年大年初二
     * @param pageId 试卷id
     * @param studentId 学生id
     *
     * @return ansVO 返回 ansVo 对象
     */
    @Transactional
    override fun getPageScore(pageId: Long, studentId: Long): AnsVO {
        val ansVO = AnsVO()
        val studentScore = studentScoreRepository.findByPagesIdAndStudentId(pageId, studentId)
                ?: throw ResultException("没有该成绩", 500)
        // 学生提交答案
        val studentAnswer = studentAnswerRepository.findByStudentIdAndPagesId(
            studentId,
            pageId
        )

        if (studentAnswer.isEmpty()) {
            throw ResultException("该学生没有参加该考试", 500)
        }

        println("studentAnswer is ${studentAnswer.size}")
        // 标准答案
        for (i in 0 until studentAnswer.size) {

            val t = titleRepository.findById(studentAnswer[i].titleId).get()
            when (t.category) {
                "1" -> {
                    val selectAns = StudentAnswerSelect()
                    selectAns.id = studentAnswer[i].titleId
                    selectAns.answer = studentAnswer[i].answer
                    selectAns.score = studentAnswer[i].score
                    selectAns.title = t.title
                    selectAns.sectionA = t.sectionA.toString()
                    selectAns.sectionB = t.sectionB.toString()
                    selectAns.sectionC = t.sectionC.toString()
                    selectAns.sectionD = t.sectionD.toString()
                    selectAns.standardAnswer = t.answer
                    ansVO.select.add(selectAns)
                }

                "2" -> {
                    val blankAnswer = com.nuc.libra.vo.StudentAnswer()
                    blankAnswer.id = studentAnswer[i].titleId
                    blankAnswer.answer = studentAnswer[i].answer
                    blankAnswer.score = studentAnswer[i].score
                    blankAnswer.title = t.title
                    blankAnswer.standardAnswer = t.answer
                    ansVO.blank.add(blankAnswer)
                }

                "3" -> {
                    val ans = com.nuc.libra.vo.StudentAnswer()
                    ans.id = studentAnswer[i].titleId
                    ans.answer = studentAnswer[i].answer
                    ans.score = studentAnswer[i].score
                    ans.title = t.title
                    ans.standardAnswer = t.answer
                    ansVO.ans.add(ans)
                }

                "4" -> {

                }
            }

        }
        ansVO.pageId = pageId
        ansVO.score = studentScore.score
        return ansVO
    }

    /**
     * 评分模块
     */
    private fun calculationScore(similarScore: Double, blankNumber: Int, score: Double): Double {
        return when (similarScore) {
            in 0.0..0.9 -> {
                0.0
            }
            in 0.9..1.0 -> {
                score / blankNumber
            }
            else -> {
                0.0
            }
        }
    }

    /**
     * 选择题评分模块
     * 和标准答案直接进行比对
     * @param standardAnswer 标准答案
     * @param studentAnswer 学生答案
     * @param score 该题分数
     */
    private fun checkChoice(studentAnswer: String, standardAnswer: String, score: Double): Double {
        if (studentAnswer === standardAnswer) {
            return score
        }
        return 0.0
    }

    /**
     * 填空题进行评分
     * 1. 检查提交的答案数是否和空数一致，如果不一致则为 **0** 分
     * 2. 对有序答案进行验证，有序答案没空进行答案校验
     * 3. 对无序答案进行验证，将无序答案拼接为一个完整的字符串进行相似度比较
     * @param studentAnswer 学生答案
     * @param standardAnswer 标准答案
     * @param score 该试题分数
     * @param isOrder 是否有序
     * @return 返回该试题分数
     */
    private fun checkBlank(studentAnswer: String, standardAnswer: String, score: Double, isOrder: Boolean): Double {
        val studentAnswerList = studentAnswer.substringAfter("【").substringBeforeLast("】").split("】\\s*?【".toRegex())
        val standardAnswerList = standardAnswer.substringAfter("【").substringBeforeLast("】").split("】\\s*?【".toRegex())
        // 填空题空的数量
        val blankNumber = standardAnswerList.size
        if (blankNumber != studentAnswerList.size) {
            return 0.0
        }
        var blankScore = 0.0
        var similar: Double
        // 有序答案
        if (isOrder) {
            for (index in 0 until blankNumber) {
                similar = NLPUtils.docSimilar(studentAnswerList[index], standardAnswerList[index])
                blankScore += calculationScore(similar, blankNumber, score)
            }
            return blankScore
        }
        // 无序答案
        else {
            val studentAnswerSb = StringBuilder()
            val standardAnswerSb = StringBuilder()
            for (ans in studentAnswerList) {
                studentAnswerSb.append(ans)
            }
            for (ans in standardAnswerList) {
                standardAnswerSb.append(ans)
            }
            similar = NLPUtils.docSimilar(studentAnswerSb.toString(), standardAnswerSb.toString())
            // x 代表着未知 所以下面的步骤只有天知道！
            // 我猜是计算平均分
            val x = 1.0 / blankNumber
            blankScore = (similar / x) * (score / blankNumber)
            return blankScore
        }
    }

    /**
     * 简单题评分
     * @param studentAnswer 学生答案
     * @param standardAnswer 标准答案
     * @param score 该题满分
     *
     * @return 学生所得分数
     */
    private fun checkQuestion(studentAnswer: String, standardAnswer: String, score: Double): Double {
        val similar = NLPUtils.docSimilar(standardAnswer, studentAnswer)
        val studentScore = (score * similar).toInt().toDouble()
        return if (studentScore < 0) {
            0.0
        } else {
            studentScore
        }

    }
}