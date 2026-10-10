package org.androidlm.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/**
 * A places.db from before 1.8 has no shops by kind or places to go for fun: a question for one
 * of those is not a places question there (it goes to Wikipedia), and the rest are as before.
 */
class PlacesCoverTest {

    @Test fun olderFile() {
        val f = File.createTempFile("places-old", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:" + f.absolutePath).use { c ->
                c.createStatement().use { st ->
                    st.executeUpdate("create table kinds(id integer primary key, name text not null unique, parents text not null)")
                    st.executeUpdate("insert into kinds values (1, 'restaurant', 'food_and_drink'), " +
                        "(2, 'vegan_restaurant', 'food_and_drink,restaurant'), (3, 'hostel', 'lodging'), " +
                        "(4, 'pharmacy', 'health_care,pharmacy_and_drug_store')")
                }
            }
            JdbcSqlDatabase(f).use { db ->
                val places = Places(db)
                assertNull(places.parse("Where are the arcades in Buenos Aires?"))
                assertNull(places.parse("Video game shops in Buenos Aires"))
                assertEquals("eat", places.parse("Tell me the best vegan restaurants in Buenos Aires")?.group)
                assertEquals("stay", places.parse("cheap hostels in Buenos Aires")?.group)
                assertEquals("pharmacy", places.parse("Where can I find a pharmacy in Buenos Aires?")?.group)
            }
        } finally {
            f.delete()
        }
    }
}
