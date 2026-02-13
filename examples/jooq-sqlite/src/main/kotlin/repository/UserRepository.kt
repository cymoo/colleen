package repository

import User
import com.example.jooq.generated.tables.Users.USERS
import config.fetchIntoClass
import config.fetchOneIntoClass
import org.jooq.DSLContext
import org.jooq.Field

class UserRepository(private val dsl: DSLContext) {

    fun findAll(): List<User> {
        return dsl.selectFrom(USERS)
            .fetchIntoClass()
    }

    fun findById(id: Int): User? {
        return dsl.selectFrom(USERS)
            .where(USERS.ID.eq(id))
            .fetchOneIntoClass()
    }

    fun findByUsername(username: String): User? {
        return dsl.selectFrom(USERS)
            .where(USERS.USERNAME.eq(username))
            .fetchOneIntoClass()
    }

    fun create(username: String, email: String): User? {
        return dsl.insertInto(USERS)
            .set(USERS.USERNAME, username)
            .set(USERS.EMAIL, email)
            .returning()
            .fetchOneIntoClass()
    }

    fun update(id: Int, username: String? = null, email: String? = null): Int {
        val map = mutableMapOf<Field<*>, Any>()

        username?.let { map[USERS.USERNAME] = it }
        email?.let { map[USERS.EMAIL] = it }

        if (map.isEmpty()) return 0

        return dsl.update(USERS)
            .set(map)
            .where(USERS.ID.eq(id))
            .execute()
    }

    fun delete(id: Int): Int {
        return dsl.deleteFrom(USERS)
            .where(USERS.ID.eq(id))
            .execute()
    }

    fun count(): Int {
        return dsl.selectCount()
            .from(USERS)
            .fetchOne(0, Int::class.java) ?: 0
    }
}