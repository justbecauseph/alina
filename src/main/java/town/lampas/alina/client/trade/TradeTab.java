package town.lampas.alina.client.trade;

import java.util.ArrayList;
import java.util.List;
import town.lampas.alina.trade.network.TradeView;

/** The tabs of the trade screen, in the order the strip shows them. */
enum TradeTab {
    BUY,
    SELL,
    EXPERIENCE;

    /** The tabs a view offers: Buy with offers, Sell with sell rules, Experience when it trades experience. */
    static List<TradeTab> available(TradeView view) {
        boolean experience = view.experience()
                .filter(e -> Exchange.experienceSellOffered(e) || Exchange.experienceBuyOffered(e))
                .isPresent();
        return available(!view.offers().isEmpty(), view.sellsItems(), experience);
    }

    static List<TradeTab> available(boolean offers, boolean sellsItems, boolean experience) {
        List<TradeTab> tabs = new ArrayList<>(3);
        if (offers) {
            tabs.add(BUY);
        }
        if (sellsItems) {
            tabs.add(SELL);
        }
        if (experience) {
            tabs.add(EXPERIENCE);
        }
        return List.copyOf(tabs);
    }

    /** The tab {@code delta} places along from {@code current} in {@code available}, wrapping at both ends. */
    static TradeTab cycle(List<TradeTab> available, TradeTab current, int delta) {
        if (available.isEmpty()) {
            return current;
        }
        int index = available.indexOf(current);
        return available.get(Math.floorMod(index + delta, available.size()));
    }
}
