package leyline.frontdoor.repo

import leyline.frontdoor.domain.Course
import leyline.frontdoor.domain.CourseId
import leyline.frontdoor.domain.PlayerId

interface CourseRepository {
    fun findById(id: CourseId): Course?
    fun findByPlayer(playerId: PlayerId): List<Course>
    fun findByPlayerAndEvent(playerId: PlayerId, eventName: String): Course?
    fun save(course: Course)
    fun delete(id: CourseId)
}
