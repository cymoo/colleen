package com.example.jooq.generated.tables;

import org.jooq.Record;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;

import java.time.LocalDateTime;

public class Users extends TableImpl<Record> {
    public static final Users USERS = new Users();

    public final TableField<Record, Integer> ID = createField(
        DSL.name("id"),
        SQLDataType.INTEGER.nullable(false).identity(true),
        this,
        ""
    );

    public final TableField<Record, String> USERNAME = createField(
        DSL.name("username"),
        SQLDataType.VARCHAR(64).nullable(false),
        this,
        ""
    );

    public final TableField<Record, String> EMAIL = createField(
        DSL.name("email"),
        SQLDataType.VARCHAR(255).nullable(false),
        this,
        ""
    );

    public final TableField<Record, LocalDateTime> CREATED_AT = createField(
        DSL.name("created_at"),
        SQLDataType.LOCALDATETIME.nullable(false),
        this,
        ""
    );

    public final TableField<Record, LocalDateTime> UPDATED_AT = createField(
        DSL.name("updated_at"),
        SQLDataType.LOCALDATETIME.nullable(false),
        this,
        ""
    );

    public Users() {
        super(DSL.name("users"));
    }

    @Override
    public Class<? extends Record> getRecordType() {
        return Record.class;
    }
}
