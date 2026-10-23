package dev.zwo.modpanel.storage;

import java.io.File;

class SqliteRepositoryTest extends RepositoryContract {

    @Override
    protected Database createDatabase() {
        return new SqliteDatabase(new File(temp, "modpanel.db"), Tables.withPrefix("modpanel_"), LOG);
    }
}
