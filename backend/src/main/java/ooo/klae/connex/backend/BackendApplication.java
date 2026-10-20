package ooo.klae.connex.backend;

import java.time.Clock;
import java.util.TimeZone;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@MapperScan("ooo.klae.connex.backend.mappers")
public class BackendApplication {

	public static void main(String[] args) {
		pinDefaultTimeZone();
		SpringApplication.run(BackendApplication.class, args);
	}

	/**
	 * Aligns JVM defaults with UTC database sessions before Spring or libraries initialize date
	 * handling, so offset-less backend timestamps never depend on the deployment host's zone.
	 */
	static void pinDefaultTimeZone() {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
