package com.nuc.evaluate.po

import javax.persistence.*

/**
 * @author 杨晓辉 2018/2/7 11:03
 */
@Entity
@Table(name = "tg_evaluate_answer")
class StudentAnswer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
    var studentId: Long = 0
    var pagesId: Long = 0
    var titleId: Long = 0
    @Column(columnDefinition = "TEXT")
    var answer: String = ""
    var score: Long = 0
    var time: java.sql.Timestamp? = null
    var employeeId: Long? = 0
    var url: String = ""
}