package net.mineacle.core.market;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.market.service.MarketExchangeService;
import net.mineacle.core.sell.SellModule;
import net.mineacle.core.sell.service.SellService;

public final class MarketModule extends Module {

    private static MarketExchangeService exchangeService;

    public static MarketExchangeService exchangeService() {
        return exchangeService;
    }

    @Override
    public String name() {
        return "Market";
    }

    @Override
    public void enable(
            Core core
    ) {
        SellService sellService =
                SellModule.sellService();

        if (sellService == null) {
            throw new IllegalStateException(
                    "Market requires Sell to initialize first"
            );
        }

        exchangeService =
                new MarketExchangeService(
                        core,
                        sellService
                );
    }

    @Override
    public void disable() {
        if (exchangeService != null) {
            exchangeService.shutdown();
            exchangeService = null;
        }
    }
}
