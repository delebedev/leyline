package leyline.domain.repo

import leyline.domain.Course
import leyline.domain.CourseId
import leyline.domain.PlayerId

interface CourseRepository {
    fun findById(id: CourseId): Course?

    fun findByPlayer(playerId: PlayerId): List<Course>

    fun findByPlayerAndEvent(
        playerId: PlayerId,
        eventName: String,
    ): Course?

    fun save(course: Course)

    fun delete(id: CourseId)
}
