package ooo.klae.connex.backend.mappers;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.PreparedStatement;
import java.sql.Types;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

class AiIdentifierKeyTypeHandlerTest {
    @Test
    void handlersBindBothProjectionsFromTheSameRawName() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        new AiIdentifierLiteralKeyTypeHandler().setParameter(statement, 1, "㍿ [Team](person:1)", JdbcType.LONGVARCHAR);
        new AiIdentifierLabelKeyTypeHandler().setParameter(statement, 2, "㍿ [Team](person:1)", JdbcType.LONGVARCHAR);
        verify(statement).setString(1, "株式会社 [team](person:1)");
        verify(statement).setString(2, "株式会社 team");
    }

    @Test
    void emptyIsProcessedAndNullIsUnprocessed() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        var literal = new AiIdentifierLiteralKeyTypeHandler();
        var label = new AiIdentifierLabelKeyTypeHandler();
        literal.setParameter(statement, 1, "{{}}", JdbcType.LONGVARCHAR);
        label.setParameter(statement, 2, "{{}}", JdbcType.LONGVARCHAR);
        literal.setParameter(statement, 3, null, JdbcType.LONGVARCHAR);
        label.setParameter(statement, 4, null, JdbcType.LONGVARCHAR);
        verify(statement).setString(1, "");
        verify(statement).setString(2, "");
        verify(statement).setNull(3, Types.LONGVARCHAR);
        verify(statement).setNull(4, Types.LONGVARCHAR);
    }
}
