package io.github.alexvargashn.ledger;

import org.springframework.boot.SpringApplication;

public class TestLedgerApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(LedgerApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
