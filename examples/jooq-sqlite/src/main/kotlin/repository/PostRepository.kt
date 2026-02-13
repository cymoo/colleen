package repository

import Post
import PostWithAuthor
import User
import com.example.jooq.generated.tables.Posts.POSTS
import com.example.jooq.generated.tables.Users.USERS
import config.fetchIntoClass
import config.fetchOneIntoClass
import org.jooq.DSLContext
import org.jooq.Field
import java.time.LocalDateTime

class PostRepository(private val dsl: DSLContext) {

    fun findAll(): List<Post> {
        return dsl.selectFrom(POSTS)
            .orderBy(POSTS.CREATED_AT.desc())
            .fetchIntoClass()
    }

    fun findAllPublished(): List<Post> {
        return dsl.selectFrom(POSTS)
            .where(POSTS.PUBLISHED.eq(true))
            .orderBy(POSTS.CREATED_AT.desc())
            .fetchIntoClass()
    }

    fun findById(id: Int): Post? {
        return dsl.selectFrom(POSTS)
            .where(POSTS.ID.eq(id))
            .fetchOneIntoClass()
    }

    fun findByIdWithAuthor(id: Int): PostWithAuthor? {
        return dsl.select()
            .from(POSTS)
            .join(USERS).on(POSTS.USER_ID.eq(USERS.ID))
            .where(POSTS.ID.eq(id))
            .fetchOne { r ->
                PostWithAuthor(
                    post = r.into(Post::class.java),
                    author = r.into(User::class.java),
                )
            }
    }

    fun findByUserId(userId: Int): List<Post> {
        return dsl.selectFrom(POSTS)
            .where(POSTS.USER_ID.eq(userId))
            .orderBy(POSTS.CREATED_AT.desc())
            .fetchIntoClass()
    }

    fun create(userId: Int, title: String, content: String? = null, published: Boolean = false): Post? {
        return dsl.insertInto(POSTS)
            .set(POSTS.USER_ID, userId)
            .set(POSTS.TITLE, title)
            .set(POSTS.CONTENT, content)
            .set(POSTS.PUBLISHED, published)
            .returning()
            .fetchOneIntoClass()
    }

    fun update(id: Int, title: String? = null, content: String? = null, published: Boolean? = null): Int {
        val map = mutableMapOf<Field<*>, Any>()

        title?.let { map[POSTS.TITLE] = it }
        content?.let { map[POSTS.CONTENT] = it }
        published?.let { map[POSTS.PUBLISHED] = it }

        map[POSTS.UPDATED_AT] = LocalDateTime.now()

        return dsl.update(POSTS)
            .set(map)
            .where(POSTS.ID.eq(id))
            .execute()
    }

    fun delete(id: Int): Int {
        return dsl.deleteFrom(POSTS)
            .where(POSTS.ID.eq(id))
            .execute()
    }

    fun countByUserId(userId: Int): Int {
        return dsl.selectCount()
            .from(POSTS)
            .where(POSTS.USER_ID.eq(userId))
            .fetchOne(0, Int::class.java) ?: 0
    }
}