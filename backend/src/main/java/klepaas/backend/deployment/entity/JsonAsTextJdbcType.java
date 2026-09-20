package klepaas.backend.deployment.entity;

import org.hibernate.type.descriptor.jdbc.JsonAsStringJdbcType;

import java.sql.Types;

public final class JsonAsTextJdbcType extends JsonAsStringJdbcType {

    public JsonAsTextJdbcType() {
        super(Types.LONGVARCHAR, null);
    }
}
