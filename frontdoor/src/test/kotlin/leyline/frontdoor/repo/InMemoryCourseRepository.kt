package leyline.frontdoor.repo

import leyline.frontdoor.domain.Course
import leyline.frontdoor.domain.CourseId
import leyline.frontdoor.domain.PlayerId

class InMemoryCourseRepository : CourseRepository {
    private val courses = mutableMapOf<CourseId, Course>()

    override fun findById(id: CourseId) = courses[id]

    override fun findByPlayer(playerId: PlayerId) =
        courses.values.filter { it.playerId == playerId }

    override fun findByPlayerAndEvent(playerId: PlayerId, eventName: String) =
        courses.values.firstOrNull { it.playerId == playerId && it.eventName == eventName }

    override fun save(course: Course) {
        courses[course.id] = course
    }

    override fun delete(id: CourseId) {
        courses.remove(id)
    }
}
