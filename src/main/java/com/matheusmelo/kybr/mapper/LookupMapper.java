package com.matheusmelo.kybr.mapper;

import com.matheusmelo.kybr.dto.link.LookupResponse;
import com.matheusmelo.kybr.model.link.ProjecaoEmpresa;
import com.matheusmelo.kybr.util.CpfMasker;
import org.springframework.stereotype.Component;

/**
 * Company projection → lookup response: name, town, state and activity description, nothing else.
 * Any CPF in the name is masked again on output, whatever the stored value holds.
 */
@Component
public class LookupMapper {

    public LookupResponse toResponse(ProjecaoEmpresa projecao) {
        return new LookupResponse(CpfMasker.mask(projecao.razaoSocial()), projecao.municipio(), projecao.uf(),
                new LookupResponse.Cnae(projecao.cnaeDescricao()));
    }
}
