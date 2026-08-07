package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.SearchIndex;
import searchengine.model.Site;

import java.util.List;

public interface IndexRepository extends JpaRepository<SearchIndex, Long> {
    @Query("SELECT page FROM Page page WHERE page.id IN (" +
            "SELECT grouped.page.id FROM SearchIndex grouped WHERE grouped.lemma.lemma IN :lemmas " +
            "GROUP BY grouped.page.id HAVING COUNT(DISTINCT grouped.lemma.lemma) = :lemmaCount)")
    List<Page> findPagesByLemmas(@Param("lemmas") List<String> lemmas,
                                 @Param("lemmaCount") long lemmaCount);

    @Query("SELECT si.ranking FROM SearchIndex si WHERE si.page = :page AND si.lemma = :lemma")
    Float findRankByPageAndLemma(@Param("page") Page page, @Param("lemma") Lemma lemma);

    List<SearchIndex> findByPage(Page page);

    @Query("SELECT page FROM Page page WHERE page.site = :site AND page.id IN (" +
            "SELECT grouped.page.id FROM SearchIndex grouped " +
            "WHERE grouped.lemma.lemma IN :lemmas AND grouped.page.site = :site " +
            "GROUP BY grouped.page.id HAVING COUNT(DISTINCT grouped.lemma.lemma) = :lemmaCount)")
    List<Page> findPagesByLemmasAndSite(@Param("lemmas") List<String> lemmas,
                                        @Param("site") Site site,
                                        @Param("lemmaCount") long lemmaCount);

    @Query("SELECT si.page.id, SUM(si.ranking) FROM SearchIndex si " +
            "WHERE si.page IN :pages AND si.lemma.lemma IN :lemmas GROUP BY si.page.id")
    List<Object[]> sumRanksByPagesAndLemmas(@Param("pages") List<Page> pages,
                                             @Param("lemmas") List<String> lemmas);
}
